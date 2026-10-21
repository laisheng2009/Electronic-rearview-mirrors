package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

const (
	tailscaleStatePrefix  = "tunnel.tailscale.state."
	proxyCredentialHeader = "X-Byd-Extend-Proxy-Token"
	tailnetUserHeader     = "X-Byd-Extend-Tailnet-User"
)

type tailscaleProvider struct {
	runtimeDir string
	server     *tsnet.Server
	listener   net.Listener
	httpServer *http.Server
	name       string
	address    string
	public     bool
}

func newTailscaleProvider(runtimeDir string) *tailscaleProvider {
	return &tailscaleProvider{runtimeDir: runtimeDir}
}

func (p *tailscaleProvider) Authorize(name, credential string) tunnelResult {
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if p.server != nil && p.name != name {
		if p.httpServer != nil {
			return failure("provider_unavailable")
		}
		_ = p.server.Close()
		p.server = nil
	}
	if p.server == nil {
		p.name = name
		p.server = &tsnet.Server{
			Dir:      p.runtimeDir,
			Hostname: name,
			Store:    tailscaleEncryptedStateStore{},
			Logf:     func(string, ...any) {},
			UserLogf: func(string, ...any) {},
			AuthKey:  credential,
		}
	}
	if err := p.server.Start(); err != nil {
		p.server.AuthKey = ""
		return failure("provider_unavailable")
	}
	// Auth keys are one-shot input; the long-lived node identity is kept only by Store.
	p.server.AuthKey = ""
	return p.waitForStatus(8 * time.Second)
}

func (p *tailscaleProvider) Status(name string) tunnelResult {
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if p.server != nil && p.name != name && p.httpServer != nil {
		return failure("provider_unavailable")
	}
	if p.server == nil || p.name != name {
		if p.server != nil {
			_ = p.server.Close()
		}
		p.server = &tsnet.Server{
			Dir:      p.runtimeDir,
			Hostname: name,
			Store:    tailscaleEncryptedStateStore{},
			Logf:     func(string, ...any) {},
			UserLogf: func(string, ...any) {},
		}
		p.name = name
	}
	if err := p.server.Start(); err != nil {
		return failure("provider_unavailable")
	}
	return p.waitForStatus(8 * time.Second)
}

func (p *tailscaleProvider) waitForStatus(timeout time.Duration) tunnelResult {
	client, err := p.server.LocalClient()
	if err != nil {
		return failure("provider_unavailable")
	}
	deadline := time.Now().Add(timeout)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return failure("provider_unavailable")
		}
		requestTimeout := min(remaining, 2*time.Second)
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		status, statusErr := client.Status(ctx)
		cancel()
		if statusErr == nil && status != nil {
			if status.AuthURL != "" {
				return tunnelResult{State: "login_required", LoginURL: status.AuthURL}
			}
			if status.BackendState == string(ipn.Running) {
				return tunnelResult{State: "authorized", Address: tailscaleAddress(status)}
			}
		}
		if time.Until(deadline) <= 0 {
			return failure("provider_unavailable")
		}
		time.Sleep(min(150*time.Millisecond, time.Until(deadline)))
	}
}

func (p *tailscaleProvider) Start(name string, localPort int, public bool, privateProxyCredential string) tunnelResult {
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if localPort < 1 || localPort > 65535 || !localHTTPServerAvailable(localPort) {
		return failure("invalid_port")
	}
	if !public && privateProxyCredential == "" {
		return failure("private_credential_missing")
	}
	if p.httpServer != nil {
		if p.name == name && p.public == public {
			return tunnelResult{State: "serving", Address: p.address}
		}
		return failure("provider_unavailable")
	}
	status := p.Status(name)
	if status.State == "error" {
		return status
	}
	if status.State != "authorized" {
		if status.State == "login_required" {
			return status
		}
		return failure("login_required")
	}
	if p.server == nil {
		return failure("not_authorized")
	}
	var listener net.Listener
	var err error
	if public {
		listener, err = p.server.ListenFunnel("tcp", ":443", tsnet.FunnelOnly())
	} else {
		listener, err = p.server.ListenTLS("tcp", ":443")
	}
	if err != nil {
		if public {
			return failure("tailscale_funnel_failed")
		}
		return failure("tailscale_https_failed")
	}
	address := tailscaleAddressFromServer(p.server)
	if address == "" {
		_ = listener.Close()
		return failure("provider_unavailable")
	}
	proxy, err := newLoopbackProxy(localPort)
	if err != nil {
		_ = listener.Close()
		return failure("invalid_port")
	}
	handler := http.Handler(proxy)
	if !public {
		handler = p.privateAdmissionHandler(handler, privateProxyCredential)
	}
	server := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          newDiscardLogger(),
	}
	p.listener = listener
	p.httpServer = server
	p.address = address
	p.public = public
	p.name = name
	go func() { _ = server.Serve(listener) }()
	return tunnelResult{State: "serving", Address: address}
}

func (p *tailscaleProvider) privateAdmissionHandler(next http.Handler, privateProxyCredential string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The caller cannot choose either trusted header; they are replaced only after WhoIs.
		r.Header.Del(proxyCredentialHeader)
		r.Header.Del(tailnetUserHeader)
		client, err := p.server.LocalClient()
		if err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		peer, err := client.WhoIs(ctx, r.RemoteAddr)
		if err != nil || peer == nil || peer.UserProfile == nil || strings.TrimSpace(peer.UserProfile.LoginName) == "" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		r.Header.Set(proxyCredentialHeader, privateProxyCredential)
		r.Header.Set(tailnetUserHeader, peer.UserProfile.LoginName)
		next.ServeHTTP(w, r)
	})
}

func (p *tailscaleProvider) Stop() tunnelResult {
	p.stopServing()
	if p.server != nil {
		_ = p.server.Close()
		p.server = nil
	}
	p.address = ""
	return tunnelResult{State: "stopped"}
}

func (p *tailscaleProvider) Revoke(name string) tunnelResult {
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	var logoutFailed bool
	if p.server == nil {
		p.server = &tsnet.Server{
			Dir:      p.runtimeDir,
			Hostname: name,
			Store:    tailscaleEncryptedStateStore{},
			Logf:     func(string, ...any) {},
			UserLogf: func(string, ...any) {},
		}
		p.name = name
		if err := p.server.Start(); err != nil {
			logoutFailed = true
		}
	}
	if p.server != nil && !logoutFailed {
		client, err := p.server.LocalClient()
		if err != nil {
			logoutFailed = true
		} else {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			status, statusErr := client.Status(ctx)
			if statusErr != nil {
				logoutFailed = true
			} else if status != nil && status.AuthURL == "" {
				logoutFailed = client.Logout(ctx) != nil
			}
			cancel()
		}
	}
	p.stopServing()
	if p.server != nil {
		_ = p.server.Close()
		p.server = nil
	}
	if err := deleteSecretPrefix(tailscaleStatePrefix); err != nil {
		return failure("secure_store_error")
	}
	p.address = ""
	if logoutFailed {
		return failure("provider_unavailable")
	}
	return tunnelResult{State: "revoked"}
}

func (p *tailscaleProvider) stopServing() {
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

func (p *tailscaleProvider) CloseForModeChange() {
	p.stopServing()
	if p.server != nil {
		_ = p.server.Close()
		p.server = nil
	}
}

type tailscaleEncryptedStateStore struct{}

func (tailscaleEncryptedStateStore) ReadState(id ipn.StateKey) ([]byte, error) {
	value, err := readSecret(tailscaleStatePrefix + string(id))
	if errorsIsSecretMissing(err) {
		return nil, ipn.ErrStateNotExist
	}
	if err != nil {
		return nil, err
	}
	return value, nil
}

func (tailscaleEncryptedStateStore) WriteState(id ipn.StateKey, value []byte) error {
	return writeSecret(tailscaleStatePrefix+string(id), value)
}

func localHTTPServerAvailable(port int) bool {
	connection, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 400*time.Millisecond)
	if err != nil {
		return false
	}
	_ = connection.Close()
	return true
}

func newLoopbackProxy(port int) (*httputil.ReverseProxy, error) {
	target, err := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", port))
	if err != nil {
		return nil, err
	}
	targetAddress := fmt.Sprintf("127.0.0.1:%d", port)
	transport := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" || address != targetAddress {
				return nil, fmt.Errorf("blocked camera proxy target")
			}
			return (&net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}).DialContext(ctx, network, targetAddress)
		},
		DisableCompression: true,
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	originalDirector := proxy.Director
	proxy.Director = func(request *http.Request) {
		externalHost := request.Host
		originalDirector(request)
		request.Host = externalHost
	}
	proxy.Transport = transport
	proxy.ErrorLog = newDiscardLogger()
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, _ error) {
		http.Error(w, "camera stream unavailable", http.StatusBadGateway)
	}
	return proxy, nil
}

func tailscaleAddress(status *ipnstate.Status) string {
	if status == nil || len(status.CertDomains) == 0 {
		return ""
	}
	return httpsAddress(status.CertDomains[0])
}

func tailscaleAddressFromServer(server *tsnet.Server) string {
	client, err := server.LocalClient()
	if err != nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := client.Status(ctx)
	if err != nil {
		return ""
	}
	return tailscaleAddress(status)
}

func httpsAddress(host string) string {
	host = strings.TrimSuffix(strings.TrimSpace(host), ".")
	if host == "" || strings.ContainsAny(host, "/?#@") {
		return ""
	}
	return "https://" + host
}

func newDiscardLogger() *log.Logger {
	return log.New(io.Discard, "", 0)
}

func errorsIsSecretMissing(err error) bool { return errors.Is(err, errSecretMissing) }

func validDeviceName(value string) bool {
	if len(value) < 1 || len(value) > 63 || value[0] == '-' || value[len(value)-1] == '-' {
		return false
	}
	for _, char := range value {
		if !((char >= 'a' && char <= 'z') || (char >= '0' && char <= '9') || char == '-') {
			return false
		}
	}
	return true
}
