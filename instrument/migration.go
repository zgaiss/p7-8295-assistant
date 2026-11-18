package main

import (
	"os"
	"path/filepath"
)

// The namespace itself is the fence. Any type (including an empty directory,
// unknown owner schema, or dangling symlink) means legacy writers must stop.
// Missing is the only permissive result; I/O/permission/ENOTDIR errors deny.
// This is an accidental-overwrite fence for upgraded participants, NOT a
// security boundary against old root FTP clients or an old installed boot.
func legacyUpdateAllowed(dir string) bool {
	_, err := os.Lstat(filepath.Join(dir, "update-v1"))
	return os.IsNotExist(err)
}

// Once migration is observed, a transient deletion cannot reopen this process's
// legacy updater. Migration still MUST stop old writers: rechecks are not locks.
type legacyUpdateFence struct{ blocked bool }

func (f *legacyUpdateFence) allowed(dir string) bool {
	if f.blocked {
		return false
	}
	if !legacyUpdateAllowed(dir) {
		f.blocked = true
		return false
	}
	return true
}
