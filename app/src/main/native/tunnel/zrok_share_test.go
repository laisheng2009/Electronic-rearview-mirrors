package main

import (
	"encoding/json"
	"testing"

	"github.com/openziti/zrok/v2/rest_model_zrok"
)

func TestStoredZrokShareKeepsItsEndpointAcrossMetadataReordering(t *testing.T) {
	// Inputs to the production validator, not substituted SDK/network outcomes.
	var saved storedZrokShare
	if err := json.Unmarshal([]byte(`{"token":"saved","address":"https://kept.example.org"}`), &saved); err != nil {
		t.Fatal(err)
	}
	detail := &rest_model_zrok.Share{ShareToken: "saved", EnvZID: "environment", ShareMode: "public", BackendMode: "proxy",
		FrontendEndpoints: []string{"another.example.org", "kept.example.org"}}
	restored, err := validateStoredZrokShare(&saved, detail, "environment")
	if err != nil || *restored != saved {
		t.Fatalf("saved endpoint changed: %v, %v", restored, err)
	}
	for _, field := range []string{"token", "environment", "mode", "backend", "endpoint"} {
		t.Run(field, func(t *testing.T) {
			changed := *detail
			switch field {
			case "token":
				changed.ShareToken = "different"
			case "environment":
				changed.EnvZID = "different"
			case "mode":
				changed.ShareMode = "private"
			case "backend":
				changed.BackendMode = "tcpTunnel"
			case "endpoint":
				changed.FrontendEndpoints = []string{"different.example.org"}
			}
			if _, err := validateStoredZrokShare(&saved, &changed, "environment"); err == nil {
				t.Fatal("changed publication silently accepted")
			}
		})
	}
	// A canceled creation can persist only the token; recover its endpoint from metadata.
	restored, err = validateStoredZrokShare(&storedZrokShare{Token: "saved"}, detail, "environment")
	if err != nil || restored.Address != "https://another.example.org" {
		t.Fatalf("incomplete saved publication was not recovered: %v, %v", restored, err)
	}
	if _, err := validateStoredZrokShare(&saved, nil, "environment"); err == nil {
		t.Fatal("absent publication accepted")
	}
}
