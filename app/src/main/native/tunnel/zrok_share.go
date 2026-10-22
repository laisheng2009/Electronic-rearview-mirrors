package main

import (
	"errors"

	"github.com/openziti/zrok/v2/rest_model_zrok"
)

type storedZrokShare struct {
	Token   string `json:"token"`
	Address string `json:"address"`
}

// Resume only the saved publication owned by this environment, without changing its URL.
func validateStoredZrokShare(saved *storedZrokShare, detail *rest_model_zrok.Share, environment string) (*storedZrokShare, error) {
	if detail == nil || saved == nil || saved.Token == "" || environment == "" ||
		detail.ShareToken != saved.Token || detail.EnvZID != environment ||
		detail.ShareMode != "public" || detail.BackendMode != "proxy" {
		return nil, errors.New("saved publication is missing or its identity/mode no longer matches")
	}
	address, _ := firstHttpsEndpoint(detail.FrontendEndpoints)
	if saved.Address != "" {
		address = ""
		for _, endpoint := range detail.FrontendEndpoints {
			if normalized, _ := normalizeZrokEndpoint(endpoint); normalized == saved.Address {
				address = normalized
				break
			}
		}
	}
	if address == "" {
		return nil, errors.New("saved publication no longer has its HTTPS endpoint")
	}
	return &storedZrokShare{Token: saved.Token, Address: address}, nil
}
