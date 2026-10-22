package main

import (
	"crypto/sha256"
	"fmt"
	"regexp"
)

var legacyStateKey = regexp.MustCompile(`^[A-Za-z0-9._-]{1,160}$`)

func tailscaleStorageKey(id string) string {
	return fmt.Sprintf("tunnel.tailscale.sdk.%x", sha256.Sum256([]byte(id)))
}
