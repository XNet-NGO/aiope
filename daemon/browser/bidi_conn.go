// Package browser contains AIOPE's WebDriver BiDi browser-driving support.
//
// bidi_conn.go is a minimal WebDriver BiDi WebSocket client, ported to Go from
// mozilla/pilo's bidiConnection.ts. It opens a WebSocket, sends commands as
// {id, method, params}, correlates responses by id, and fans out unsolicited
// BiDi events to subscribers. Transport is local-only (ws://127.0.0.1:<port>).
package browser

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// DefaultCommandTimeout is applied to SendCommand when no per-call timeout is set.
const DefaultCommandTimeout = 30 * time.Second

// Event is an unsolicited BiDi event (no id): {type:"event", method, params}.
type Event struct {
	Method string
	Params map[string]any
}

// EventHandler receives BiDi events. It must not block; heavy work should be
// dispatched to another goroutine.
type EventHandler func(Event)

type pendingCommand struct {
	ch chan bidiResponse
}

type bidiResponse struct {
	result json.RawMessage
	err    error
}

// wire message shape for outgoing commands
type outgoing struct {
	ID     int            `json:"id"`
	Method string         `json:"method"`
	Params map[string]any `json:"params"`
}

// BiDiConnection is a minimal WebDriver BiDi client over a single WebSocket.
type BiDiConnection struct {
	url string

	mu      sync.Mutex
	conn    *websocket.Conn
	nextID  int
	pending map[int]*pendingCommand
	closed  bool

	writeMu sync.Mutex // serialize writes; gorilla forbids concurrent writers

	handlersMu sync.RWMutex
	handlers   []EventHandler

	defaultTimeout time.Duration
	done           chan struct{}
}

// NewBiDiConnection creates a client for the given ws:// URL. Call Connect to open.
func NewBiDiConnection(url string) *BiDiConnection {
	return &BiDiConnection{
		url:            url,
		nextID:         1,
		pending:        make(map[int]*pendingCommand),
		defaultTimeout: DefaultCommandTimeout,
		done:           make(chan struct{}),
	}
}

// Connect opens the WebSocket and starts the read loop.
func (c *BiDiConnection) Connect(ctx context.Context) error {
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, _, err := dialer.DialContext(ctx, c.url, nil)
	if err != nil {
		return fmt.Errorf("bidi dial %s: %w", c.url, err)
	}
	c.mu.Lock()
	c.conn = conn
	c.mu.Unlock()
	go c.readLoop()
	return nil
}

// OnEvent registers a handler for unsolicited BiDi events.
func (c *BiDiConnection) OnEvent(h EventHandler) {
	c.handlersMu.Lock()
	c.handlers = append(c.handlers, h)
	c.handlersMu.Unlock()
}

// SendCommand sends a BiDi command and awaits the correlated response.
// A zero timeout uses the connection default.
func (c *BiDiConnection) SendCommand(ctx context.Context, method string, params map[string]any, timeout time.Duration) (json.RawMessage, error) {
	if params == nil {
		params = map[string]any{}
	}
	if timeout <= 0 {
		timeout = c.defaultTimeout
	}

	c.mu.Lock()
	if c.closed || c.conn == nil {
		c.mu.Unlock()
		return nil, fmt.Errorf("bidi: not connected")
	}
	id := c.nextID
	c.nextID++
	pc := &pendingCommand{ch: make(chan bidiResponse, 1)}
	c.pending[id] = pc
	conn := c.conn
	c.mu.Unlock()

	msg := outgoing{ID: id, Method: method, Params: params}
	c.writeMu.Lock()
	err := conn.WriteJSON(msg)
	c.writeMu.Unlock()
	if err != nil {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, fmt.Errorf("bidi write %s: %w", method, err)
	}

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case resp := <-pc.ch:
		return resp.result, resp.err
	case <-timer.C:
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, fmt.Errorf("bidi timeout waiting for %s (id=%d) after %s", method, id, timeout)
	case <-ctx.Done():
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, ctx.Err()
	}
}

// Close shuts the connection and fails all pending commands.
func (c *BiDiConnection) Close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	conn := c.conn
	c.conn = nil
	pending := c.pending
	c.pending = make(map[int]*pendingCommand)
	c.mu.Unlock()

	close(c.done)
	if conn != nil {
		_ = conn.Close()
	}
	for _, pc := range pending {
		pc.ch <- bidiResponse{err: fmt.Errorf("bidi: connection closed")}
	}
}

func (c *BiDiConnection) readLoop() {
	for {
		c.mu.Lock()
		conn := c.conn
		closed := c.closed
		c.mu.Unlock()
		if closed || conn == nil {
			return
		}

		_, data, err := conn.ReadMessage()
		if err != nil {
			c.failAll(fmt.Errorf("bidi read: %w", err))
			return
		}
		c.dispatch(data)
	}
}

func (c *BiDiConnection) dispatch(data []byte) {
	// Peek at the shape: responses have a numeric "id"; events have "type":"event".
	var probe struct {
		ID     *int            `json:"id"`
		Type   string          `json:"type"`
		Method string          `json:"method"`
		Result json.RawMessage `json:"result"`
		Error  string          `json:"error"`
		Msg    string          `json:"message"`
		Params map[string]any  `json:"params"`
	}
	if err := json.Unmarshal(data, &probe); err != nil {
		return // ignore malformed frames, matching the TS client
	}

	if probe.ID == nil {
		// Unsolicited event.
		if probe.Type == "event" {
			c.handlersMu.RLock()
			handlers := c.handlers
			c.handlersMu.RUnlock()
			ev := Event{Method: probe.Method, Params: probe.Params}
			for _, h := range handlers {
				h(ev)
			}
		}
		return
	}

	c.mu.Lock()
	pc := c.pending[*probe.ID]
	delete(c.pending, *probe.ID)
	c.mu.Unlock()
	if pc == nil {
		return
	}

	if probe.Type == "error" {
		pc.ch <- bidiResponse{err: fmt.Errorf("bidi error: %s — %s", probe.Error, probe.Msg)}
		return
	}
	pc.ch <- bidiResponse{result: probe.Result}
}

func (c *BiDiConnection) failAll(reason error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	if c.conn != nil {
		_ = c.conn.Close()
		c.conn = nil
	}
	pending := c.pending
	c.pending = make(map[int]*pendingCommand)
	c.mu.Unlock()

	for _, pc := range pending {
		pc.ch <- bidiResponse{err: reason}
	}
}
