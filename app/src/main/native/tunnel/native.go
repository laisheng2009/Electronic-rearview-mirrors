package main

/*
#include <stdlib.h>
#include "jni_bridge.h"
*/
import "C"

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"unsafe"

	"github.com/sirupsen/logrus"
)

type tunnelRequest struct {
	Provider               string `json:"provider"`
	InstanceID             string `json:"instanceId"`
	Name                   string `json:"name"`
	Credential             string `json:"credential"`
	LocalPort              int    `json:"localPort"`
	Public                 bool   `json:"public"`
	PrivateProxyCredential string `json:"privateProxyCredential"`
}

func main() {}

func init() {
	// Provider libraries must not write credentials, identities, or local state to logs.
	log.SetOutput(io.Discard)
	logrus.SetOutput(io.Discard)
}

type tunnelResult struct {
	State       string `json:"state"`
	Address     string `json:"address,omitempty"`
	LoginURL    string `json:"loginUrl,omitempty"`
	ErrorCode   string `json:"errorCode,omitempty"`
	ErrorDetail string `json:"errorDetail,omitempty"`
}

type tunnelProvider interface {
	Authorize(name, credential string) tunnelResult
	Status(name string) tunnelResult
	Start(name string, localPort int, public bool, privateProxyCredential string) tunnelResult
	Stop() tunnelResult
	Revoke(name string) tunnelResult
}

type registeredProvider struct {
	mu       sync.Mutex
	provider tunnelProvider
	closed   bool
}

var (
	providerMu             sync.Mutex
	providers              = map[string]*registeredProvider{}
	runtimeDir             string
	networkEventGeneration atomic.Uint64
	networkWorkerRunning   atomic.Bool
)

//export camera_tunnel_set_runtime_dir
func camera_tunnel_set_runtime_dir(path *C.char) C.int {
	requestedPath := C.GoString(path)
	if requestedPath == "" {
		return 0
	}
	requested, err := filepath.Abs(requestedPath)
	if err != nil {
		return 0
	}
	providerMu.Lock()
	defer providerMu.Unlock()
	if runtimeDir != "" && runtimeDir != requested {
		return 0
	}
	absolute, err := prepareRuntimeDirectory(requested)
	if err != nil {
		return 0
	}
	if runtimeDir != "" && runtimeDir != absolute {
		return 0
	}
	runtimeDir = absolute
	return 1
}

func prepareRuntimeDirectory(path string) (string, error) {
	if path == "" {
		return "", errors.New("private runtime directory missing")
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	logs := filepath.Join(absolute, "logs")
	for _, directory := range []string{absolute, logs} {
		if err := os.MkdirAll(directory, 0700); err != nil {
			return "", err
		}
		if err := os.Chmod(directory, 0700); err != nil {
			return "", err
		}
	}
	if err := os.Setenv("TS_LOGS_DIR", logs); err != nil {
		return "", err
	}
	return absolute, nil
}

//export camera_tunnel_network_changed
func camera_tunnel_network_changed() {
	networkEventGeneration.Add(1)
	if networkWorkerRunning.CompareAndSwap(false, true) {
		go processNetworkChanges()
	}
}

func processNetworkChanges() {
	for {
		generation := networkEventGeneration.Load()
		providerMu.Lock()
		entries := make([]*registeredProvider, 0, len(providers))
		for _, entry := range providers {
			entries = append(entries, entry)
		}
		providerMu.Unlock()

		for _, entry := range entries {
			entry.mu.Lock()
			if !entry.closed {
				if provider, ok := entry.provider.(*tailscaleProvider); ok {
					provider.NetworkChanged()
				}
			}
			entry.mu.Unlock()
		}

		networkWorkerRunning.Store(false)
		if networkEventGeneration.Load() == generation ||
			!networkWorkerRunning.CompareAndSwap(false, true) {
			return
		}
	}
}

//export camera_tunnel_call
func camera_tunnel_call(operation *C.char, requestJSON *C.char) (result *C.char) {
	operationName := safeOperation(C.GoString(operation))
	stage := "decode_request"
	var entry *registeredProvider
	var entryLocked bool
	var providerKey string
	defer func() {
		if recover() != nil {
			if entryLocked {
				entry.closed = true
				safeDisposeProvider(entry.provider)
				entry.mu.Unlock()
				entryLocked = false
				removeProvider(providerKey, entry)
			}
			logNativePanic(operationName, stage, panicLocation())
			result = C.CString(encodeResult(failure("runtime_error")))
		}
	}()
	requestBytes := []byte(C.GoString(requestJSON))
	var request tunnelRequest
	if err := json.Unmarshal(requestBytes, &request); err != nil {
		wipeBytes(requestBytes)
		return C.CString(encodeResult(failure("invalid_request")))
	}
	wipeBytes(requestBytes)
	stage = "validate_request"
	if currentRuntimeDirectory() == "" {
		return C.CString(encodeResult(failure("native_unavailable")))
	}
	if !validInstanceID(request.InstanceID) {
		return C.CString(encodeResult(failure("invalid_request")))
	}
	if !validOperation(operationName) {
		return C.CString(encodeResult(failure("invalid_request")))
	}
	if request.Provider != "tailscale" && request.Provider != "zrok" {
		return C.CString(encodeResult(failure("invalid_provider")))
	}
	providerKey = request.Provider + "\x00" + request.InstanceID
	stage = "provider_lookup"
	entry = lookupProvider(providerKey, request.Provider, operationName)
	if entry == nil {
		return C.CString(encodeResult(tunnelResult{State: "stopped"}))
	}
	entry.mu.Lock()
	entryLocked = true
	if entry.closed {
		entry.mu.Unlock()
		entryLocked = false
		return C.CString(encodeResult(failure("native_unavailable")))
	}
	stage = "provider_call"
	var output tunnelResult
	switch operationName {
	case "authorize":
		output = entry.provider.Authorize(request.Name, request.Credential)
	case "status":
		output = entry.provider.Status(request.Name)
	case "start":
		output = entry.provider.Start(request.Name, request.LocalPort, request.Public, request.PrivateProxyCredential)
	case "stop":
		output = entry.provider.Stop()
	case "close":
		entry.closed = true
		output = entry.provider.Stop()
		removeProvider(providerKey, entry)
	case "revoke":
		output = entry.provider.Revoke(request.Name)
	}
	entry.mu.Unlock()
	entryLocked = false
	return C.CString(encodeResult(output))
}

func safeOperation(value string) string {
	switch value {
	case "authorize", "status", "start", "stop", "close", "revoke":
		return value
	default:
		return "unknown"
	}
}

func validOperation(value string) bool { return value != "unknown" }

func lookupProvider(key, kind, operation string) *registeredProvider {
	providerMu.Lock()
	defer providerMu.Unlock()
	entry := providers[key]
	if entry == nil {
		if operation == "stop" || operation == "close" {
			return nil
		}
		var provider tunnelProvider
		switch kind {
		case "tailscale":
			provider = newTailscaleProvider(runtimeDir)
		case "zrok":
			provider = newZrokProvider(runtimeDir)
		}
		entry = &registeredProvider{provider: provider}
		providers[key] = entry
	}
	return entry
}

func currentRuntimeDirectory() string {
	providerMu.Lock()
	defer providerMu.Unlock()
	return runtimeDir
}

func removeProvider(key string, entry *registeredProvider) {
	providerMu.Lock()
	if providers[key] == entry {
		delete(providers, key)
	}
	providerMu.Unlock()
}

func safeDisposeProvider(provider tunnelProvider) {
	defer func() { _ = recover() }()
	switch provider := provider.(type) {
	case *tailscaleProvider:
		provider.discardAfterFailure()
	case *zrokProvider:
		provider.discardAfterFailure()
	}
}

func panicLocation() string {
	var pcs [20]uintptr
	count := runtime.Callers(2, pcs[:])
	frames := runtime.CallersFrames(pcs[:count])
	for {
		frame, more := frames.Next()
		if !strings.HasPrefix(frame.Function, "runtime.") &&
			!strings.Contains(frame.Function, "camera_tunnel_call.func") &&
			frame.Function != "main.camera_tunnel_call" && frame.Function != "main.panicLocation" {
			if frame.Function != "" {
				return frame.Function
			}
		}
		if !more {
			return "unknown"
		}
	}
}

func logNativePanic(operation, stage, location string) {
	cOperation := C.CString(operation)
	cStage := C.CString(stage)
	cLocation := C.CString(location)
	defer C.free(unsafe.Pointer(cOperation))
	defer C.free(unsafe.Pointer(cStage))
	defer C.free(unsafe.Pointer(cLocation))
	C.camera_tunnel_log_panic(cOperation, cStage, cLocation)
}

//export camera_tunnel_free
func camera_tunnel_free(value *C.char) {
	if value != nil {
		C.free(unsafe.Pointer(value))
	}
}

var errSecretMissing = errors.New("secret missing")

func readSecret(key string) ([]byte, error) {
	cKey := C.CString(key)
	defer C.free(unsafe.Pointer(cKey))
	var value *C.char
	var length C.size_t
	status := int(C.camera_secret_get(cKey, &value, &length))
	if status == 0 {
		return nil, errSecretMissing
	}
	if status < 0 || value == nil || uint64(length) > uint64(^uint32(0)>>1) {
		if value != nil {
			C.camera_secret_clear(unsafe.Pointer(value), length)
			C.free(unsafe.Pointer(value))
		}
		return nil, errors.New("secure store read failed")
	}
	defer func() {
		C.camera_secret_clear(unsafe.Pointer(value), length)
		C.free(unsafe.Pointer(value))
	}()
	return C.GoBytes(unsafe.Pointer(value), C.int(length)), nil
}

func writeSecret(key string, value []byte) error {
	cKey := C.CString(key)
	defer C.free(unsafe.Pointer(cKey))
	var cValue unsafe.Pointer
	if len(value) != 0 {
		cValue = C.CBytes(value)
		defer func() {
			C.camera_secret_clear(cValue, C.size_t(len(value)))
			C.free(cValue)
		}()
	}
	if C.camera_secret_put(cKey, (*C.char)(cValue), C.size_t(len(value))) == 0 {
		return errors.New("secure store write failed")
	}
	return nil
}

func deleteSecret(key string) error {
	cKey := C.CString(key)
	defer C.free(unsafe.Pointer(cKey))
	if C.camera_secret_remove(cKey) == 0 {
		return errors.New("secure store remove failed")
	}
	return nil
}

func deleteSecretPrefix(prefix string) error {
	cPrefix := C.CString(prefix)
	defer C.free(unsafe.Pointer(cPrefix))
	if C.camera_secret_remove_prefix(cPrefix) == 0 {
		return errors.New("secure store remove failed")
	}
	return nil
}

func failure(code string) tunnelResult {
	details := map[string]string{
		"invalid_request":            "The tunnel request is invalid.",
		"invalid_provider":           "The selected tunnel provider is not supported.",
		"invalid_name":               "Use a valid lowercase device or connection name.",
		"invalid_port":               "The local camera server is not available on the requested port.",
		"credential_required":        "Enter the provider credential to authorize this tunnel.",
		"login_required":             "Complete provider sign-in before starting the tunnel.",
		"not_authorized":             "Authorize this provider before starting the tunnel.",
		"private_credential_missing": "Private camera access could not be established.",
		"provider_unavailable":       "The tunnel provider could not complete the request. Check its account and network settings.",
		"tailscale_https_failed":     "Private Tailscale HTTPS access could not be started.",
		"tailscale_funnel_failed":    "Tailscale Funnel could not be started.",
		"zrok_enable_failed":         "The vehicle could not be registered with Zrok.",
		"zrok_share_failed":          "The Zrok viewing link could not be created.",
		"secure_store_error":         "Encrypted tunnel state could not be read or saved.",
		"revoke_required":            "Revoke the current Zrok account before authorizing another account.",
		"native_unavailable":         "The secure tunnel runtime is unavailable on this device.",
		"runtime_error":              "The secure tunnel runtime could not complete the request.",
	}
	detail, ok := details[code]
	if !ok {
		code, detail = "runtime_error", details["runtime_error"]
	}
	return tunnelResult{State: "error", ErrorCode: code, ErrorDetail: detail}
}

func encodeResult(result tunnelResult) string {
	encoded, err := json.Marshal(result)
	if err != nil {
		return `{"state":"error","errorCode":"runtime_error","errorDetail":"The secure tunnel runtime could not complete the request."}`
	}
	return string(encoded)
}

func wipeBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}

func validInstanceID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index, char := range value {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if char != '-' {
				return false
			}
			continue
		}
		if !((char >= '0' && char <= '9') || (char >= 'a' && char <= 'f')) {
			return false
		}
	}
	return true
}
