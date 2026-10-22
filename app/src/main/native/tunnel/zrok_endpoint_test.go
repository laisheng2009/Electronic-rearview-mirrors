package main

import (
	"strings"
	"testing"
)

func TestZrokEndpointNormalization(t *testing.T) {
	for _, test := range []struct{ input, want, form string }{
		{" camera.public.example.org ", "https://camera.public.example.org", "bare_hostname"},
		{"https://camera.example.org", "https://camera.example.org", "https_url"},
		{"https://camera.example.org:8443/view?key=private", "https://camera.example.org:8443/view?key=private", "https_url"},
		{"https://[2001:db8::1]:443/", "https://[2001:db8::1]:443/", "https_url"},
		{"", "", "empty"},
		{"http://camera.example.org", "", "unsupported_scheme"},
		{"ftp://camera.example.org", "", "unsupported_scheme"},
		{"https://user:secret@camera.example.org", "", "userinfo"},
		{"camera.example.org/path", "", "invalid_hostname"},
		{"camera.example.org?password=secret", "", "invalid_hostname"},
		{"user@camera.example.org", "", "invalid_hostname"},
		{"//camera.example.org", "", "invalid_hostname"},
		{"https://", "", "invalid_hostname"},
		{"https://bad_host.example.org", "", "invalid_hostname"},
		{"-bad.example.org", "", "invalid_hostname"},
		{"bad..example.org", "", "invalid_hostname"},
		{strings.Repeat("a", 64) + ".example.org", "", "invalid_hostname"},
		{"https://camera.example.org:0", "", "invalid_port"},
		{"https://camera.example.org:65536", "", "invalid_port"},
		{"https://camera.example.org:", "", "invalid_port"},
		{"https://camera.example.org:abc", "", "malformed_url"},
		{"https://camera.example.org/%zz", "", "malformed_url"},
	} {
		t.Run(test.input, func(t *testing.T) {
			got, form := normalizeZrokEndpoint(test.input)
			if got != test.want || form != test.form {
				t.Fatalf("got (%q, %q), want (%q, %q)", got, form, test.want, test.form)
			}
		})
	}
}

func TestZrokEndpointSelectionAndPrivateDiagnostics(t *testing.T) {
	address, diagnostic := firstHttpsEndpoint([]string{
		"http://rejected-secret.example.org", "private-share.public.example.org",
		"https://later-share.example.org?password=do-not-log", "",
	})
	if address != "https://private-share.public.example.org" {
		t.Fatalf("wrong first usable endpoint: %q", address)
	}
	want := "endpoint_count=4 forms=map[bare_hostname:1 empty:1 https_url:1 unsupported_scheme:1] selected_index=1 selected_scheme=https"
	if diagnostic != want {
		t.Fatalf("response-shape diagnostic leaked input or omitted selection: %q", diagnostic)
	}
	for _, endpoints := range [][]string{nil, {"invalid/host", "https://secret@private.example.org"}} {
		address, diagnostic = firstHttpsEndpoint(endpoints)
		if address != "" || !strings.Contains(diagnostic, "selected_index=-1 selected_scheme=none") {
			t.Fatalf("unusable response selected an endpoint: %q %q", address, diagnostic)
		}
		if strings.Contains(diagnostic, "secret") || strings.Contains(diagnostic, "private") {
			t.Fatalf("diagnostic leaked rejected input: %q", diagnostic)
		}
	}
}
