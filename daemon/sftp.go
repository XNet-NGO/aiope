package main

import (
	"github.com/charmbracelet/log"
	"github.com/charmbracelet/ssh"
	"github.com/pkg/sftp"
)

func SftpHandler(sess ssh.Session) {
	log.Info("SFTP session started", "user", sess.User(), "remote", sess.RemoteAddr())

	srv, err := sftp.NewServer(sess)
	if err != nil {
		log.Error("SFTP server creation failed", "err", err)
		return
	}

	if err := srv.Serve(); err != nil {
		if err.Error() != "EOF" {
			log.Error("SFTP server error", "err", err)
		}
	}

	log.Info("SFTP session ended", "user", sess.User())
}
