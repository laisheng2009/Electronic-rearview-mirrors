package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/go-openapi/runtime"
	httptransport "github.com/go-openapi/runtime/client"
	"github.com/go-openapi/strfmt"
	"github.com/openziti/sdk-golang/ziti"
	"github.com/openziti/sdk-golang/ziti/edge"
	"github.com/openziti/zrok/v2/environment/env_core"
	"github.com/openziti/zrok/v2/rest_client_zrok"
	"github.com/openziti/zrok/v2/rest_client_zrok/metadata"
	restShare "github.com/openziti/zrok/v2/rest_client_zrok/share"
	"github.com/openziti/zrok/v2/sdk/golang/sdk"
)

const (
	zrokAPIEndpoint      = "https://api-v2.zrok.io"
	zrokSecretPrefix     = "tunnel.zrok."
	zrokEnvironmentKey   = zrokSecretPrefix + "environment"
	zrokIdentityKey      = zrokSecretPrefix + "identity.environment"
	zrokShareKey         = zrokSecretPrefix + "active_share"
	zrokOperationTimeout = 30 * time.Second
)

type zrokProvider struct {
	instanceID      string
	serving         *servingRun
	zitiContext     ziti.Context
	runtimeDir      string
	listener        edge.Listener
	httpServer      *http.Server
	name            string
	address         string
	share           *storedZrokShare
	operationMu     sync.Mutex
	operationCancel context.CancelFunc
	cancelPending   bool
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

func shareToken(shares ...*storedZrokShare) string {
	for _, share := range shares {
		if share != nil && share.Token != "" {
			return share.Token
		}
	}
	return ""
}

func newZrokProvider(runtimeDir string) *zrokProvider {
	return &zrokProvider{runtimeDir: runtimeDir}
}

func (p *zrokProvider) beginOperation() (context.Context, func()) {
	ctx, cancel := context.WithTimeout(context.Background(), zrokOperationTimeout)
	p.operationMu.Lock()
	cancelPending := p.cancelPending
	p.cancelPending = false
	p.operationCancel = cancel
	p.operationMu.Unlock()
	if cancelPending {
		cancel()
	}
	return ctx, func() {
		cancel()
		p.operationMu.Lock()
		p.operationCancel = nil
		p.operationMu.Unlock()
	}
}

func (p *zrokProvider) cancelOperation() {
	p.operationMu.Lock()
	cancel := p.operationCancel
	if cancel == nil {
		p.cancelPending = true
	}
	p.operationMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (p *zrokProvider) clearPendingCancellation() {
	p.operationMu.Lock()
	p.cancelPending = false
	p.operationMu.Unlock()
}

func withZrokCleanupContext(root *memoryRoot, cleanup func() error) error {
	if root == nil {
		return cleanup()
	}
	previous := root.operationContext
	ctx, cancel := context.WithTimeout(context.Background(), zrokOperationTimeout)
	root.operationContext = ctx
	defer func() {
		cancel()
		root.operationContext = previous
	}()
	return cleanup()
}

func zrokOperationFailure(ctx context.Context, code, stage string, err error, root *memoryRoot, secrets ...string) tunnelResult {
	if errors.Is(ctx.Err(), context.Canceled) {
		return tunnelResult{State: "cancelled", ErrorDetail: nativeErrorDetail(stage, errors.Join(ctx.Err(), err), secrets...)}
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return zrokFailure("zrok_operation_timeout", stage, errors.Join(ctx.Err(), err), root, secrets...)
	}
	return zrokFailure(code, stage, err, root, secrets...)
}

func zrokFailure(code, stage string, err error, root *memoryRoot, secrets ...string) tunnelResult {
	if errors.Is(err, errZrokEnvironmentMissing) {
		code = "zrok_environment_missing"
	}
	if root != nil && root.Environment() != nil {
		secrets = append(secrets, root.Environment().AccountToken)
	}
	result := failureWithDiagnostic(code, stage, err, secrets...)
	var apiErr *runtime.APIError
	if err != nil && errors.As(err, &apiErr) {
		result.ErrorDetail += fmt.Sprintf(" http_status=%d api_operation=%s", apiErr.Code,
			safeNativeText(apiErr.OperationName, secrets...))
	}
	return result
}

func zrokFailureReason(code, stage, reason string, root *memoryRoot, secrets ...string) tunnelResult {
	if root != nil && root.Environment() != nil {
		secrets = append(secrets, root.Environment().AccountToken)
	}
	return failureWithReason(code, stage, reason, secrets...)
}

func zrokSecondary(result tunnelResult, stage string, err error, root *memoryRoot, secrets ...string) tunnelResult {
	if root != nil && root.Environment() != nil {
		secrets = append(secrets, root.Environment().AccountToken)
	}
	return addSecondaryDiagnostic(result, stage, err, secrets...)
}

func (p *zrokProvider) Authorize(name, credential string) tunnelResult {
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return zrokOperationFailure(ctx, "zrok_enable_failed", "authorize", ctx.Err(), nil, credential)
	}
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	root, err := loadMemoryRoot()
	if err != nil {
		return zrokFailure("secure_store_error", "authorize.load_environment", err, nil, credential)
	}
	root.operationContext = ctx
	if credential == "" {
		if root.IsEnabled() {
			return tunnelResult{State: "authorized"}
		}
		return failure("credential_required")
	}
	if root.IsEnabled() {
		if root.Environment().AccountToken == credential {
			return tunnelResult{State: "authorized"}
		}
		return failure("revoke_required")
	}
	root.SetEnvironmentInMemory(&env_core.Environment{
		AccountToken: credential,
		ApiEndpoint:  zrokAPIEndpoint,
	})
	enabled, err := sdk.EnableEnvironment(root, &sdk.EnableRequest{
		Host:        name,
		Description: "BYD Extend camera stream",
	})
	if err != nil {
		return zrokOperationFailure(ctx, "zrok_enable_failed", "authorize.enable_environment", err, root, credential)
	}
	if enabled == nil || enabled.ZitiIdentity == "" || enabled.ZitiConfig == "" {
		return zrokFailureReason("zrok_enable_failed", "authorize.enable_environment", "response missing identity or configuration", root, credential)
	}
	root.SetEnvironmentInMemory(&env_core.Environment{
		AccountToken: credential,
		ZitiIdentity: enabled.ZitiIdentity,
		ApiEndpoint:  zrokAPIEndpoint,
	})
	root.environmentHost = name
	if err := root.SaveZitiIdentityNamed(root.EnvironmentIdentityName(), enabled.ZitiConfig); err != nil {
		result := zrokFailure("secure_store_error", "authorize.persist_identity", err, root, credential)
		return zrokSecondary(result, "authorize.disable_after_identity_failure", withZrokCleanupContext(root, func() error {
			return sdk.DisableEnvironment(enabled, root)
		}), root, credential)
	}
	if err := root.persistEnvironment(); err != nil {
		result := zrokFailure("secure_store_error", "authorize.persist_environment", err, root, credential)
		result = zrokSecondary(result, "authorize.disable_after_persist_failure", withZrokCleanupContext(root, func() error {
			return sdk.DisableEnvironment(enabled, root)
		}), root, credential)
		return zrokSecondary(result, "authorize.remove_identity_after_persist_failure", deleteSecret(zrokIdentityKey), root, credential)
	}
	return tunnelResult{State: "authorized"}
}

func (p *zrokProvider) Status(name string) tunnelResult {
	if p.serving != nil {
		if failed := p.serving.failure.Load(); failed != nil {
			return *failed
		}
	}
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return zrokOperationFailure(ctx, "secure_store_error", "status", ctx.Err(), nil)
	}
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if p.httpServer != nil {
		return tunnelResult{State: "serving", Address: p.address}
	}
	root, err := loadMemoryRoot()
	if err != nil {
		return zrokFailure("secure_store_error", "status.load_environment", err, nil)
	}
	root.operationContext = ctx
	if !root.IsEnabled() {
		return failure("not_authorized")
	}
	if p.share == nil {
		p.share, err = loadZrokShare()
		if err != nil {
			return zrokFailure("secure_store_error", "status.load_share", err, root)
		}
	}
	return tunnelResult{State: "authorized"}
}

func (p *zrokProvider) Start(name string, localPort int, public bool, _ string) (result tunnelResult) {
	if p.serving != nil {
		if failed := p.serving.failure.Load(); failed != nil {
			return *failed
		}
	}
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return zrokOperationFailure(ctx, "zrok_share_failed", "start", ctx.Err(), nil)
	}
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
		return failureWithReason("provider_unavailable", "start.replace_share", "the current Zrok share is already serving a different connection label")
	}
	root, err := loadMemoryRoot()
	if err != nil {
		return zrokFailure("secure_store_error", "start.load_environment", err, nil)
	}
	root.operationContext = ctx
	if !root.IsEnabled() {
		return failure("not_authorized")
	}
	staleShare, loadErr := loadZrokShare()
	if loadErr != nil {
		return zrokFailure("secure_store_error", "start.load_previous_share", loadErr, root)
	}
	if staleShare != nil {
		if err := deleteZrokShare(root, &sdk.Share{Token: staleShare.Token}); err != nil {
			return zrokOperationFailure(ctx, "provider_unavailable", "start.delete_previous_share", err, root, staleShare.Token)
		}
		if err := deleteSecret(zrokShareKey); err != nil {
			return zrokFailure("secure_store_error", "start.remove_previous_share", err, root, staleShare.Token)
		}
		p.share = nil
	}
	target := fmt.Sprintf("http://127.0.0.1:%d", localPort)
	share, err := sdk.CreateShare(root, &sdk.ShareRequest{
		BackendMode:    sdk.ProxyBackendMode,
		ShareMode:      sdk.PublicShareMode,
		Target:         target,
		NameSelections: []sdk.NameSelection{{NamespaceToken: "public"}},
	})
	if err != nil {
		return zrokOperationFailure(ctx, "zrok_share_failed", "start.create_share", err, root)
	}
	var endpoints []string
	if share != nil {
		endpoints = share.FrontendEndpoints
	}
	address, diagnostic := firstHttpsEndpoint(endpoints)
	// Carry response shape through success, cancellation and later listener/cleanup failures.
	defer func() { result.Diagnostic = "stage=start.create_share_response " + diagnostic }()
	if share == nil || share.Token == "" {
		return zrokFailureReason("zrok_share_failed", "start.create_share", "response missing share token", root)
	}
	if ctx.Err() != nil {
		p.share = &storedZrokShare{Token: share.Token}
		result := zrokOperationFailure(ctx, "zrok_share_failed", "start.create_share", ctx.Err(), root, share.Token)
		if err := persistZrokShare(p.share); err != nil {
			result = zrokSecondary(result, "start.persist_cancelled_share_cleanup_reference", err, root, share.Token)
		}
		return result
	}
	if address == "" {
		result := zrokFailureReason("zrok_endpoint_failed", "start.create_share_response", "response contained no HTTPS public endpoint", root, share.Token)
		if err := withZrokCleanupContext(root, func() error { return deleteZrokShare(root, share) }); err != nil {
			result = zrokSecondary(result, "start.delete_share_without_endpoint", err, root, share.Token)
			if persistErr := persistZrokShare(&storedZrokShare{Token: share.Token}); persistErr != nil {
				result = zrokSecondary(result, "start.persist_share_cleanup_reference", persistErr, root, share.Token)
			}
			p.share = &storedZrokShare{Token: share.Token}
		}
		return result
	}
	p.share = &storedZrokShare{Token: share.Token, Address: address}
	if err := persistZrokShare(p.share); err != nil {
		result := zrokFailure("secure_store_error", "start.persist_share", err, root, share.Token)
		if cleanupErr := withZrokCleanupContext(root, func() error { return deleteZrokShare(root, share) }); cleanupErr != nil {
			result = zrokSecondary(result, "start.delete_share_after_persist_failure", cleanupErr, root, share.Token)
			if persistErr := persistZrokShare(p.share); persistErr != nil {
				result = zrokSecondary(result, "start.persist_share_cleanup_reference", persistErr, root, share.Token)
			}
		} else {
			p.share = nil
		}
		return result
	}
	listener, zctx, err := openZrokListener(share.Token)
	if zctx != nil {
		defer func() {
			if p.zitiContext != zctx {
				zctx.Close()
			}
		}()
	}
	if err != nil {
		result := zrokOperationFailure(ctx, "zrok_identity_listener_failed", "start.open_share_listener", err, root, share.Token)
		if cleanupErr := withZrokCleanupContext(root, func() error { return deleteZrokShare(root, share) }); cleanupErr == nil {
			if removeErr := deleteSecret(zrokShareKey); removeErr == nil {
				p.share = nil
			} else {
				result = zrokSecondary(result, "start.remove_failed_share_record", removeErr, root, share.Token)
			}
		} else {
			result = zrokSecondary(result, "start.delete_share_after_listener_failure", cleanupErr, root, share.Token)
		}
		return result
	}
	if ctx.Err() != nil {
		if err := listener.Close(); err != nil {
			result := zrokOperationFailure(ctx, "zrok_identity_listener_failed", "start.open_share_listener", ctx.Err(), root, share.Token)
			return zrokSecondary(result, "start.close_listener_after_cancellation", err, root, share.Token)
		}
		return zrokOperationFailure(ctx, "zrok_identity_listener_failed", "start.open_share_listener", ctx.Err(), root, share.Token)
	}
	proxy, err := newLoopbackProxy(localPort, "zrok", p.instanceID)
	if err != nil {
		result := zrokFailure("invalid_port", "start.create_loopback_proxy", err, root, share.Token)
		if closeErr := listener.Close(); closeErr != nil {
			result = zrokSecondary(result, "start.close_listener_after_proxy_failure", closeErr, root, share.Token)
		}
		if cleanupErr := withZrokCleanupContext(root, func() error { return deleteZrokShare(root, share) }); cleanupErr == nil {
			if removeErr := deleteSecret(zrokShareKey); removeErr == nil {
				p.share = nil
			} else {
				result = zrokSecondary(result, "start.remove_share_after_proxy_failure", removeErr, root, share.Token)
			}
		} else {
			result = zrokSecondary(result, "start.delete_share_after_proxy_failure", cleanupErr, root, share.Token)
		}
		return result
	}
	server := &http.Server{
		Handler:           http.Handler(proxy),
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          log.New(diagnosticWriter{"zrok", "http generation=" + p.instanceID}, "", 0),
	}
	p.listener = listener
	p.zitiContext = zctx
	p.httpServer = server
	p.name = name
	p.address = address
	p.serving = serveTunnel("zrok", p.instanceID, server, listener)
	return tunnelResult{State: "serving", Address: address}
}

func (p *zrokProvider) Stop() tunnelResult {
	ctx, finish := p.beginOperation()
	defer finish()
	stopErr := p.stopServing()
	root, err := loadMemoryRoot()
	if errors.Is(err, errSecretMissing) {
		share, shareErr := loadZrokShare()
		if shareErr != nil {
			result := zrokFailure("secure_store_error", "stop.load_share_without_environment", shareErr, nil)
			return zrokSecondary(result, "stop.close_listener", stopErr, nil)
		}
		if share != nil || p.share != nil {
			result := failureWithReason("provider_unavailable", "stop.load_environment", "active share exists without an encrypted environment")
			return zrokSecondary(result, "stop.close_listener", stopErr, nil, shareToken(share, p.share))
		}
		if stopErr != nil {
			return zrokFailure("provider_unavailable", "stop.close_listener", stopErr, nil)
		}
		return tunnelResult{State: "stopped"}
	}
	if err != nil {
		result := zrokFailure("secure_store_error", "stop.load_environment", err, nil)
		return zrokSecondary(result, "stop.close_listener", stopErr, nil)
	}
	root.operationContext = ctx
	share := p.share
	if share == nil {
		share, err = loadZrokShare()
	}
	if err != nil {
		result := zrokFailure("secure_store_error", "stop.load_share", err, root)
		return zrokSecondary(result, "stop.close_listener", stopErr, root)
	}
	if share != nil && !root.IsEnabled() {
		result := failureWithReason("provider_unavailable", "stop.delete_share", "active share exists without an enabled environment", shareToken(share))
		return zrokSecondary(result, "stop.close_listener", stopErr, root, shareToken(share))
	}
	if share != nil {
		if err := withZrokCleanupContext(root, func() error {
			return deleteZrokShare(root, &sdk.Share{Token: share.Token})
		}); err != nil {
			result := zrokOperationFailure(ctx, "provider_unavailable", "stop.delete_share", err, root, share.Token)
			return zrokSecondary(result, "stop.close_listener", stopErr, root, share.Token)
		}
		if err := deleteSecret(zrokShareKey); err != nil {
			result := zrokFailure("secure_store_error", "stop.remove_share_record", err, root, share.Token)
			return zrokSecondary(result, "stop.close_listener", stopErr, root, share.Token)
		}
	}
	p.share = nil
	p.address = ""
	if stopErr != nil {
		return zrokFailure("provider_unavailable", "stop.close_listener", stopErr, root, shareToken(share))
	}
	return tunnelResult{State: "stopped"}
}

func (p *zrokProvider) Revoke(_ string) tunnelResult {
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return zrokOperationFailure(ctx, "provider_unavailable", "revoke", ctx.Err(), nil)
	}
	stopErr := p.stopServing()
	root, err := loadMemoryRoot()
	if errors.Is(err, errSecretMissing) {
		if share, shareErr := loadZrokShare(); shareErr != nil {
			result := zrokFailure("secure_store_error", "revoke.load_share_without_environment", shareErr, nil)
			return zrokSecondary(result, "revoke.close_listener", stopErr, nil)
		} else if share != nil {
			result := failureWithReason("provider_unavailable", "revoke.load_environment", "active share exists without an encrypted environment", share.Token)
			return zrokSecondary(result, "revoke.close_listener", stopErr, nil, share.Token)
		}
		if err := deleteSecretPrefix(zrokSecretPrefix); err != nil {
			result := zrokFailure("secure_store_error", "revoke.delete_state", err, nil)
			return zrokSecondary(result, "revoke.close_listener", stopErr, nil)
		}
		if stopErr != nil {
			return zrokFailure("provider_unavailable", "revoke.close_listener", stopErr, nil)
		}
		return tunnelResult{State: "revoked"}
	}
	if err != nil {
		result := zrokFailure("secure_store_error", "revoke.load_environment", err, nil)
		return zrokSecondary(result, "revoke.close_listener", stopErr, nil)
	}
	root.operationContext = ctx
	share, shareErr := p.share, error(nil)
	if share == nil {
		share, shareErr = loadZrokShare()
	}
	if shareErr != nil {
		result := zrokFailure("secure_store_error", "revoke.load_share", shareErr, root)
		return zrokSecondary(result, "revoke.close_listener", stopErr, root)
	}
	if share != nil && !root.IsEnabled() {
		result := failureWithReason("provider_unavailable", "revoke.delete_share", "active share exists without an enabled environment", share.Token)
		return zrokSecondary(result, "revoke.close_listener", stopErr, root, share.Token)
	}
	environmentAbsent := false
	if share != nil {
		err := deleteZrokShare(root, &sdk.Share{Token: share.Token})
		environmentAbsent = errors.Is(err, errZrokEnvironmentMissing)
		if err != nil && !environmentAbsent {
			result := zrokOperationFailure(ctx, "provider_unavailable", "revoke.delete_share", err, root, share.Token)
			return zrokSecondary(result, "revoke.close_listener", stopErr, root, share.Token)
		}
		if err := deleteSecret(zrokShareKey); err != nil {
			result := zrokFailure("secure_store_error", "revoke.remove_share_record", err, root, share.Token)
			return zrokSecondary(result, "revoke.close_listener", stopErr, root, share.Token)
		}
		p.share = nil
	}
	if root.IsEnabled() && !environmentAbsent {
		env := &sdk.Environment{Host: root.environmentHost, ZitiIdentity: root.Environment().ZitiIdentity}
		if err := sdk.DisableEnvironment(env, root); err != nil {
			result := zrokOperationFailure(ctx, "provider_unavailable", "revoke.disable_environment", err, root)
			return zrokSecondary(result, "revoke.close_listener", stopErr, root)
		}
	}
	if err := deleteSecretPrefix(zrokSecretPrefix); err != nil {
		result := zrokFailure("secure_store_error", "revoke.delete_state", err, root)
		return zrokSecondary(result, "revoke.close_listener", stopErr, root)
	}
	p.share = nil
	p.address = ""
	if stopErr != nil {
		return zrokFailure("provider_unavailable", "revoke.close_listener", stopErr, root)
	}
	return tunnelResult{State: "revoked"}
}

func (p *zrokProvider) stopServing() error {
	if p.serving != nil {
		p.serving.expected.Store(true)
		p.serving = nil
	}
	var cleanupErr error
	if p.httpServer != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		if err := p.httpServer.Shutdown(ctx); err != nil {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("shutdown Zrok HTTPS server: %w", err))
		}
		cancel()
		p.httpServer = nil
	}
	if p.listener != nil {
		if err := p.listener.Close(); err != nil {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("close Zrok listener: %w", err))
		}
		p.listener = nil
	}
	if p.zitiContext != nil {
		p.zitiContext.Close()
		p.zitiContext = nil
	}
	return cleanupErr
}

func (p *zrokProvider) discardAfterFailure() {
	_ = p.stopServing()
}

type memoryRoot struct {
	metadata         env_core.Metadata
	config           *env_core.Config
	environment      *env_core.Environment
	environmentHost  string
	operationContext context.Context
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
		return nil, fmt.Errorf("encrypted environment data is malformed: %w", err)
	}
	if stored.AccountToken == "" || stored.ZitiIdentity == "" || stored.ApiEndpoint != zrokAPIEndpoint || stored.Host == "" {
		return nil, errors.New("encrypted environment data is incomplete")
	}
	identity, err := readSecret(zrokIdentityKey)
	if err != nil {
		return nil, fmt.Errorf("encrypted Ziti identity is unavailable: %w", err)
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
	transport.Context = r.operationContext
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
	return "", errors.New("identity paths are unsupported; use the owned in-memory Ziti context")
}

func openZrokListener(token string) (edge.Listener, ziti.Context, error) {
	data, err := readSecret(zrokIdentityKey)
	if err != nil {
		return nil, nil, fmt.Errorf("read Ziti identity: %w", err)
	}
	defer wipeBytes(data)
	var config ziti.Config
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, nil, fmt.Errorf("decode Ziti identity: %w", err)
	}
	ctx, err := ziti.NewContext(&config)
	if err != nil {
		return nil, nil, fmt.Errorf("create Ziti context: %w", err)
	}
	owned := false
	defer func() {
		if !owned {
			ctx.Close()
		}
	}()
	listener, err := ctx.ListenWithOptions(token, &ziti.ListenOptions{
		ConnectTimeout: 30 * time.Second, WaitForNEstablishedListeners: 1,
	})
	if err != nil {
		return nil, nil, fmt.Errorf("listen Ziti: %w", err)
	}
	owned = true
	return listener, ctx, nil
}

var errZrokEnvironmentMissing = errors.New("zrok environment no longer exists")

func deleteZrokShare(root *memoryRoot, share *sdk.Share) error {
	err := sdk.DeleteShare(root, share)
	var absent *restShare.UnshareNotFound
	if !errors.As(err, &absent) {
		return err
	}
	// The same 404 also means a missing environment; verify it before continuing.
	client, clientErr := root.Client()
	if clientErr != nil {
		return errors.Join(err, clientErr)
	}
	response, lookupErr := client.Metadata.Overview(metadata.NewOverviewParams(),
		httptransport.APIKeyAuth("X-TOKEN", "header", root.Environment().AccountToken))
	if lookupErr != nil {
		return errors.Join(err, lookupErr)
	}
	if response == nil || response.Payload == nil {
		return err
	}
	for _, entry := range response.Payload.Environments {
		if entry != nil && entry.Environment != nil && entry.Environment.ZID == root.Environment().ZitiIdentity {
			nativeDiagnostic("zrok", "unshare", "authenticated share already absent; environment exists")
			return nil
		}
	}
	return errZrokEnvironmentMissing
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
