package main

import (
	"strings"
	"testing"
)

func TestStateKeysAreSafeDistinctAndDoNotOverlapLegacyIdentity(t *testing.T) {
	keys := []string{"_machinekey", "_serve/profile", "_serve_profile", "", "ключ/帳戶", strings.Repeat("a/", 1000)}
	seen := map[string]bool{}
	for _, raw := range keys {
		key := tailscaleStorageKey(raw)
		if !legacyStateKey.MatchString(key) || key != tailscaleStorageKey(raw) || seen[key] {
			t.Fatalf("invalid or colliding mapped key for %q", raw)
		}
		if strings.HasPrefix(key, "tunnel.tailscale.state.") {
			t.Fatal("new key overlaps legacy namespace")
		}
		seen[key] = true
	}
	if legacyStateKey.MatchString("tunnel.tailscale.state._serve/profile") {
		t.Fatal("unsafe legacy key accepted")
	}
}
