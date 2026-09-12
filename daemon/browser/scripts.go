package browser

import (
	"fmt"
	"strings"
)

// This file holds the JS snippets injected via Eval (BiDi script.evaluate /
// CDP Runtime.Evaluate) and shared helpers. Behavior mirrors AIOPE's on-device
// WebBrowser.kt so local (WebView/GeckoView) and remote (BiDi/CDP) tools behave
// identically for the agent.

// paginate trims text to [offset, offset+limit) with a continuation hint.
func paginate(text string, offset, limit int) string {
	runes := []rune(text)
	n := len(runes)
	if offset < 0 {
		offset = 0
	}
	if offset > n {
		offset = n
	}
	end := n
	if limit > 0 && offset+limit < n {
		end = offset + limit
	}
	out := string(runes[offset:end])
	if end < n {
		out += fmt.Sprintf("\n...(truncated. Use offset=%d to read more)", end)
	}
	return out
}

// jsEscape escapes a string for single-quoted JS embedding.
func jsEscape(s string) string {
	s = strings.ReplaceAll(s, "\\", "\\\\")
	s = strings.ReplaceAll(s, "'", "\\'")
	s = strings.ReplaceAll(s, "\n", "\\n")
	s = strings.ReplaceAll(s, "\r", "")
	return s
}

func clickScript(selector string) string {
	sel := jsEscape(selector)
	return fmt.Sprintf(`(function(){
  var el = document.querySelector('%s');
  if (!el) return 'Element not found: %s';
  el.click();
  return 'Clicked: ' + (el.tagName||'') + ' ' + (el.textContent||'').substring(0,50);
})()`, sel, sel)
}

func fillScript(selector, value string) string {
	sel := jsEscape(selector)
	val := jsEscape(value)
	// React/Vue-safe fill: use the native value setter (bypassing framework value
	// overrides) + a proper InputEvent so controlled inputs on SPAs (X/Reddit/etc.)
	// register the value. Matches the on-device WebBrowser.kt actuation.
	return fmt.Sprintf(`(function(){
  var el = document.querySelector('%s');
  if (!el) return 'Element not found: %s';
  el.focus();
  var v = '%s';
  var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
  var nativeSetter = desc && desc.set;
  try { if (nativeSetter) { nativeSetter.call(el, v); } else { el.value = v; } } catch (e) { el.value = v; }
  if (el._valueTracker) { el._valueTracker.setValue(''); }
  el.dispatchEvent(new InputEvent('input', {bubbles:true, data:v, inputType:'insertText'}));
  el.dispatchEvent(new Event('change', {bubbles:true}));
  return 'Filled: ' + (el.tagName||'') + ' with ' + (el.value||'').substring(0,50);
})()`, sel, sel, val)
}

func scrollScript(dir ScrollDir, px int) string {
	d := px
	if dir == ScrollUp {
		d = -px
	}
	return fmt.Sprintf(
		`(function(){ window.scrollBy(0, %d); return 'Scrolled %s %dpx, now at ' + window.scrollY; })()`,
		d, dir, px,
	)
}

// elementsScript lists interactive elements with synthesized selectors.
// Ported from WebBrowser.kt getElements().
const elementsScript = `(function(){
  var els = document.querySelectorAll('a,button,input,select,textarea,[role=button],[onclick]');
  var out = [];
  for (var i = 0; i < els.length; i++) {
    var e = els[i];
    var r = e.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    var sel = '';
    if (e.id) { sel = '#' + e.id; }
    else if (e.name) { sel = e.tagName.toLowerCase() + '[name="' + e.name + '"]'; }
    else if (e.getAttribute && e.getAttribute('aria-label')) { sel = '[aria-label="' + e.getAttribute('aria-label').replace(/"/g,'\\"') + '"]'; }
    else if (e.type && e.tagName === 'INPUT') { sel = 'input[type="' + e.type + '"]'; }
    else { sel = e.tagName.toLowerCase(); }
    var label = '';
    if (e.textContent && e.textContent.trim()) label += e.textContent.trim().substring(0,50);
    else if (e.value) label += 'val=' + e.value.substring(0,30);
    else if (e.placeholder) label += 'placeholder=' + e.placeholder.substring(0,30);
    out.push('[' + (out.length+1) + '] ' + sel + (label ? ' — "' + label + '"' : ''));
  }
  return out.join('\n');
})()`
