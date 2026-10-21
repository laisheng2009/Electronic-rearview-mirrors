package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/go-openapi/runtime"
	httptransport "github.com/go-openapi/runtime/client"
	"github.com/go-openapi/strfmt"
	"github.com/openziti/sdk-golang/ziti/edge"
	"github.com/openziti/zrok/v2/environment/env_core"
	"github.com/openziti/zrok/v2/rest_client_zrok"
	"github.com/openziti/zrok/v2/sdk/golang/sdk"
	"golang.org/x/sys/unix"
)

const (
	zrokAPIEndpoint    = "https://api-v2.zrok.io"
	zrokSecretPrefix   = "tunnel.zrok."
	zrokEnvironmentKey = zrokSecretPrefix + "environment"
	zrokIdentityKey    = zrokSecretPrefix + "identity.environment"
	zrokShareKey       = zrokSecretPrefix + "active_share"
)

type zrokProvider struct {
	runtimeDir string
	listener   edge.Listener
	httpServer *http.Server
	name       string
	address    string
	share      *storedZrokShare
}

type storedZrokEnvironment struct {
	AccountToken string `json:"accountToken"`
	ZitiIdentity string `json:"zitiIdentity"`
	ApiEndpoint  string `json:"apiEndpoint"`
	Host         string `json:"host"`
}

type storedZrokShare struct {
	Token   string `json:"token"`
	Address string `json:"address"`
}

func newZrokProvider(runtimeDir string) *zrokProvider {
	return &zrokProvider{runtimeDir: runtimeDir}
}

func (p *zrokProvider) Authorize(name, credential string) tunnelResult {
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	root, err := loadMemoryRoot()
	if err != nil {
		return failure("secure_store_error")
	}
	defer root.CloseMemfds()
	if credential == "" {
		if root.IsEnabled() {
			return tunnelResult{State: "authorized"}
		}
		return failure("credential_required")
	}
	if root.IsEnabled() && root.Environment().AccountToken == credential {
		return tunnelResult{State: "authorized"}
	}
	if root.IsEnabled() {
		if p.httpServer != nil {
			return failure("provider_unavailable")
		}
		if oldShare, loadErr := loadZrokShare(); loadErr != nil {
			return failure("secure_store_error")
		} else if oldShare != nil {
			if err := sdk.DeleteShare(root, &sdk.Share{Token: oldShare.Token}); err != nil {
				return failure("provider_unavailable")
			}
			if err := deleteSecret(zrokShareKey); err != nil {
				return failure("secure_store_error")
			}
			p.share = nil
			p.address = ""
		}
		if sdk.DisableEnvironment(&sdk.Environment{
			Host:         root.environmentHost,
			ZitiIdentity: root.Environment().ZitiIdentity,
		}, root) != nil {
			return failure("provider_unavailable")
		}
		if err := deleteSecretPrefix(zrokSecretPrefix); err != nil {
			return failure("secure_store_error")
		}
		root, err = loadMemoryRoot()
		if err != nil {
			return failure("secure_store_error")
		}
		defer root.CloseMemfds()
	}
	root.SetEnvironmentInMemory(&env_core.Environment{
		AccountToken: credential,
		ApiEndpoint:  zrokAPIEndpoint,
	})
	enabled, err := sdk.EnableEnvironment(root, &sdk.EnableRequest{
		Host:        name,
		Description: "BYD Extend camera stream",
	})
	if err != nil || enabled == nil || enabled.ZitiIdentity == "" || enabled.ZitiConfig == "" {
		return failure("provider_unavailable")
	}
	root.SetEnvironmentInMemory(&env_core.Environment{
		AccountToken: credential,
		ZitiIdentity: enabled.ZitiIdentity,
		ApiEndpoint:  zrokAPIEndpoint,
	})
	root.environmentHost = name
	if err := root.SaveZitiIdentityNamed(root.EnvironmentIdentityName(), enabled.ZitiConfig); err != nil {
		_ = sdk.DisableEnvironment(enabled, root)
		return failure("secure_store_error")
	}
	if err := root.persistEnvironment(); err != nil {
		_ = sdk.DisableEnvironment(enabled, root)
		_ = deleteSecret(zrokIdentityKey)
		return failure("secure_store_error")
	}
	return tunnelResult{State: "authorized"}
}

func (p *zrokProvider) Status(name string) tunnelResult {
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if p.httpServer != nil {
		return tunnelResult{State: "serving", Address: p.address}
	}
	root, err := loadMemoryRoot()
	if err != nil {
		return failure("secure_store_error")
	}
	defer root.CloseMemfds()
	if !root.IsEnabled() {
		return failure("not_authorized")
	}
	if p.share == nil {
		p.share, err = loadZrokShare()
		if err != nil {
			return failure("secure_store_error")
		}
	}
	return tunnelResult{State: "authorized"}
}

func (p *zrokProvider) Start(name string, localPort int, public bool, _ string) tunnelResult {
	if !public {
		return failure("invalid_request")
	}
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if localPort < 1 || localPort > 65535 || !localHTTPServerAvailable(localPort) {
		return failure("invalid_port")
	}
	if p.httpServer != nil {
		if p.name == name {
			return tunnelResult{State: "serving", Address: p.address}
		}
		return failure("provider_unavailable")
	}
	root, err := loadMemoryRoot()
	if err != nil {
		return failure("secure_store_error")
	}
	defer root.CloseMemfds()
	if !root.IsEnabled() {
		return failure("not_authorized")
	}
	if staleShare, loadErr := loadZrokShare(); loadErr == nil && staleShare != nil {
		if err := sdk.DeleteShare(root, &sdk.Share{Token: staleShare.Token}); err != nil {
			return failure("provider_unavailable")
		}
		if err := deleteSecret(zrokShareKey); err != nil {
			return failure("secure_store_error")
		}
		p.share = nil
	}
	target := fmt.Sprintf("http://127.0.0.1:%d", localPort)
	share, err := sdk.CreateShare(root, &sdk.ShareRequest{
		BackendMode:    sdk.ProxyBackendMode,
		ShareMode:      sdk.PublicShareMode,
		Target:         target,
		NameSelections: []sdk.NameSelection{{NamespaceToken: "public", Name: name}},
	})
	if err != nil || share == nil || share.Token == "" {
		return failure("provider_unavailable")
	}
	address := firstHttpsEndpoint(share.FrontendEndpoints)
	if address == "" {
		if err := sdk.DeleteShare(root, share); err != nil {
			_ = persistZrokShare(&storedZrokShare{Token: share.Token})
			p.share = &storedZrokShare{Token: share.Token}
		}
		return failure("provider_unavailable")
	}
	p.share = &storedZrokShare{Token: share.Token, Address: address}
	if err := persistZrokShare(p.share); err != nil {
		if cleanupErr := sdk.DeleteShare(root, share); cleanupErr != nil {
			_ = sdk.DisableEnvironment(&sdk.Environment{Host: root.environmentHost, ZitiIdentity: root.Environment().ZitiIdentity}, root)
		}
		p.share = nil
		return failure("secure_store_error")
	}
	listener, err := sdk.NewListener(share.Token, root)
	if err != nil {
		if cleanupErr := sdk.DeleteShare(root, share); cleanupErr == nil {
			_ = deleteSecret(zrokShareKey)
			p.share = nil
		}
		return failure("provider_unavailable")
	}
	proxy, err := newLoopbackProxy(localPort)
	if err != nil {
		_ = listener.Close()
		if cleanupErr := sdk.DeleteShare(root, share); cleanupErr == nil {
			_ = deleteSecret(zrokShareKey)
			p.share = nil
		}
		return failure("invalid_port")
	}
	server := &http.Server{
		Handler:           http.Handler(proxy),
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          newDiscardLogger(),
	}
	p.listener = listener
	p.httpServer = server
	p.name = name
	p.address = address
	go func() { _ = server.Serve(listener) }()
	return tunnelResult{State: "serving", Address: address}
}

func (p *zrokProvider) Stop() tunnelResult {
	p.stopServing()
	root, err := loadMemoryRoot()
	if errors.Is(err, errSecretMissing) {
		share, shareErr := loadZrokShare()
		if shareErr != nil || share != nil || p.share != nil {
			return failure("provider_unavailable")
		}
		return tunnelResult{State: "stopped"}
	}
	if err != nil {
		return failure("secure_store_error")
	}
	defer root.CloseMemfds()
	share := p.share
	if share == nil {
		share, err = loadZrokShare()
	}
	if err != nil {
		return failure("secure_store_error")
	}
	if share != nil && !root.IsEnabled() {
		return failure("provider_unavailable")
	}
	if share != nil {
		if err := sdk.DeleteShare(root, &sdk.Share{Token: share.Token}); err != nil {
			return failure("provider_unavailable")
		}
		if err := deleteSecret(zrokShareKey); err != nil {
			return failure("secure_store_error")
		}
	}
	p.share = nil
	p.address = ""
	return tunnelResult{State: "stopped"}
}

func (p *zrokProvider) Revoke(_ string) tunnelResult {
	p.stopServing()
	root, err := loadMemoryRoot()
	if errors.Is(err, errSecretMissing) {
		if share, shareErr := loadZrokShare(); shareErr != nil || share != nil {
			return failure("provider_unavailable")
		}
		if err := deleteSecretPrefix(zrokSecretPrefix); err != nil {
			return failure("secure_store_error")
		}
		return tunnelResult{State: "revoked"}
	}
	if err != nil {
		return failure("secure_store_error")
	}
	defer root.CloseMemfds()
	share, shareErr := p.share, error(nil)
	if share == nil {
		share, shareErr = loadZrokShare()
	}
	if shareErr != nil {
		return failure("secure_store_error")
	}
	if share != nil && !root.IsEnabled() {
		return failure("provider_unavailable")
	}
	if share != nil {
		if err := sdk.DeleteShare(root, &sdk.Share{Token: share.Token}); err != nil {
			return failure("provider_unavailable")
		}
		if err := deleteSecret(zrokShareKey); err != nil {
			return failure("secure_store_error")
		}
		p.share = nil
	}
	if root.IsEnabled() {
		env := &sdk.Environment{Host: root.environmentHost, ZitiIdentity: root.Environment().ZitiIdentity}
		if err := sdk.DisableEnvironment(env, root); err != nil {
			return failure("provider_unavailable")
		}
	}
	if err := deleteSecretPrefix(zrokSecretPrefix); err != nil {
		return failure("secure_store_error")
	}
	p.share = nil
	p.address = ""
	return tunnelResult{State: "revoked"}
}

func (p *zrokProvider) stopServing() {
	if p.httpServer != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		_ = p.httpServer.Shutdown(ctx)
		cancel()
		p.httpServer = nil
	}
	if p.listener != nil {
		_ = p.listener.Close()
		p.listener = nil
	}
}

type memoryRoot struct {
	mu              sync.Mutex
	metadata        env_core.Metadata
	config          *env_core.Config
	environment     *env_core.Environment
	environmentHost string
	identityFiles   []*os.File
}

func loadMemoryRoot() (*memoryRoot, error) {
	root := &memoryRoot{
		metadata: env_core.Metadata{V: "0.4"},
		config:   &env_core.Config{ApiEndpoint: zrokAPIEndpoint, DefaultNamespace: "public", Headless: true},
	}
	data, err := readSecret(zrokEnvironmentKey)
	if errors.Is(err, errSecretMissing) {
		return root, nil
	}
	if err != nil {
		return nil, err
	}
	defer wipeBytes(data)
	var stored storedZrokEnvironment
	if err := json.Unmarshal(data, &stored); err != nil {
		return nil, errors.New("encrypted environment data is malformed")
	}
	if stored.AccountToken == "" || stored.ZitiIdentity == "" || stored.ApiEndpoint != zrokAPIEndpoint || stored.Host == "" {
		return nil, errors.New("encrypted environment data is incomplete")
	}
	identity, err := readSecret(zrokIdentityKey)
	if err != nil {
		return nil, errors.New("encrypted Ziti identity is unavailable")
	}
	defer wipeBytes(identity)
	if len(identity) == 0 {
		return nil, errors.New("encrypted Ziti identity is empty")
	}
	root.environment = &env_core.Environment{
		AccountToken: stored.AccountToken,
		ZitiIdentity: stored.ZitiIdentity,
		ApiEndpoint:  stored.ApiEndpoint,
	}
	root.environmentHost = stored.Host
	return root, nil
}

func (r *memoryRoot) CloseMemfds() {
	r.mu.Lock()
	files := r.identityFiles
	r.identityFiles = nil
	r.mu.Unlock()
	for _, file := range files {
		_ = file.Close()
	}
}

func (r *memoryRoot) persistEnvironment() error {
	if r.environment == nil {
		return errors.New("environment is unavailable")
	}
	data, err := json.Marshal(storedZrokEnvironment{
		AccountToken: r.environment.AccountToken,
		ZitiIdentity: r.environment.ZitiIdentity,
		ApiEndpoint:  r.environment.ApiEndpoint,
		Host:         r.environmentHost,
	})
	if err != nil {
		return err
	}
	defer wipeBytes(data)
	return writeSecret(zrokEnvironmentKey, data)
}

func (r *memoryRoot) SetEnvironmentInMemory(environment *env_core.Environment) {
	r.environment = environment
}

func (r *memoryRoot) Metadata() *env_core.Metadata { return &r.metadata }
func (r *memoryRoot) Obliterate() error {
	r.CloseMemfds()
	return deleteSecretPrefix(zrokSecretPrefix)
}
func (r *memoryRoot) HasConfig() (bool, error) { return r.config != nil, nil }
func (r *memoryRoot) Config() *env_core.Config { return r.config }
func (r *memoryRoot) SetConfig(config *env_core.Config) error {
	r.config = config
	return nil
}
func (r *memoryRoot) Client() (*rest_client_zrok.Zrok, error) {
	apiEndpoint, _ := r.ApiEndpoint()
	endpoint, err := url.Parse(apiEndpoint)
	if err != nil || endpoint.Scheme != "https" || endpoint.Host == "" {
		return nil, errors.New("invalid Zrok API endpoint")
	}
	transport := httptransport.New(endpoint.Host, "/api/v2", []string{endpoint.Scheme})
	transport.Producers["application/zrok.v1+json"] = runtime.JSONProducer()
	transport.Consumers["application/zrok.v1+json"] = runtime.JSONConsumer()
	return rest_client_zrok.New(transport, strfmt.Default), nil
}
func (r *memoryRoot) ApiEndpoint() (string, string) {
	if r.environment != nil && r.environment.ApiEndpoint != "" {
		return r.environment.ApiEndpoint, "encrypted-environment"
	}
	if r.config != nil && r.config.ApiEndpoint != "" {
		return r.config.ApiEndpoint, "in-memory-config"
	}
	return zrokAPIEndpoint, "built-in"
}
func (r *memoryRoot) DefaultNamespace() (string, string) { return "public", "built-in" }
func (r *memoryRoot) Headless() (bool, string)           { return true, "built-in" }
func (r *memoryRoot) SuperNetwork() (bool, string)       { return false, "built-in" }
func (r *memoryRoot) IsEnabled() bool {
	return r.environment != nil && r.environment.ZitiIdentity != ""
}
func (r *memoryRoot) Environment() *env_core.Environment { return r.environment }
func (r *memoryRoot) SetEnvironment(environment *env_core.Environment) error {
	r.environment = environment
	return r.persistEnvironment()
}
func (r *memoryRoot) DeleteEnvironment() error {
	r.environment = nil
	return deleteSecret(zrokEnvironmentKey)
}
func (*memoryRoot) PublicIdentityName() string      { return "public" }
func (*memoryRoot) EnvironmentIdentityName() string { return "environment" }
func (r *memoryRoot) ZitiIdentityNamed(name string) (string, error) {
	if name != r.EnvironmentIdentityName() {
		return "", errors.New("unknown encrypted Ziti identity")
	}
	config, err := readSecret(zrokIdentityKey)
	if err != nil {
		return "", err
	}
	defer wipeBytes(config)
	fd, err := unix.MemfdCreate("byd-zrok-identity", unix.MFD_CLOEXEC)
	if err != nil {
		return "", errors.New("in-memory identity file is unavailable")
	}
	file := os.NewFile(uintptr(fd), "byd-zrok-identity")
	if file == nil {
		_ = unix.Close(fd)
		return "", errors.New("in-memory identity file is unavailable")
	}
	if _, err := file.Write(config); err != nil {
		_ = file.Close()
		return "", errors.New("could not load encrypted identity")
	}
	if _, err := file.Seek(0, 0); err != nil {
		_ = file.Close()
		return "", errors.New("could not load encrypted identity")
	}
	r.mu.Lock()
	r.identityFiles = append(r.identityFiles, file)
	r.mu.Unlock()
	return fmt.Sprintf("/proc/self/fd/%d", fd), nil
}
func (r *memoryRoot) SaveZitiIdentityNamed(name, data string) error {
	if name != r.EnvironmentIdentityName() || data == "" {
		return errors.New("invalid encrypted Ziti identity")
	}
	secret := []byte(data)
	defer wipeBytes(secret)
	return writeSecret(zrokIdentityKey, secret)
}
func (r *memoryRoot) DeleteZitiIdentityNamed(name string) error {
	if name != r.EnvironmentIdentityName() {
		return errors.New("unknown encrypted Ziti identity")
	}
	return deleteSecret(zrokIdentityKey)
}
func (*memoryRoot) AgentSocket() (string, error) {
	return "", errors.New("agent sockets are unsupported")
}
func (*memoryRoot) AgentRegistry() (string, error) {
	return "", errors.New("agent registry is unsupported")
}
func (*memoryRoot) AgentEnrollment() (string, error) {
	return "", errors.New("agent enrollment is unsupported")
}

func loadZrokShare() (*storedZrokShare, error) {
	data, err := readSecret(zrokShareKey)
	if errors.Is(err, errSecretMissing) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer wipeBytes(data)
	var share storedZrokShare
	if err := json.Unmarshal(data, &share); err != nil || share.Token == "" {
		return nil, errors.New("encrypted share data is malformed")
	}
	return &share, nil
}

func persistZrokShare(share *storedZrokShare) error {
	data, err := json.Marshal(share)
	if err != nil {
		return err
	}
	defer wipeBytes(data)
	return writeSecret(zrokShareKey, data)
}

func firstHttpsEndpoint(endpoints []string) string {
	for _, endpoint := range endpoints {
		parsed, err := url.Parse(strings.TrimSpace(endpoint))
		if err == nil && parsed.Scheme == "https" && parsed.Host != "" && parsed.User == nil {
			return parsed.String()
		}
	}
	return ""
}
