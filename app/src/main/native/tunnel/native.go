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
	"sync"
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

var (
	providerMu sync.Mutex
	providers  = map[string]tunnelProvider{}
	runtimeDir string
)

//export camera_tunnel_set_runtime_dir
func camera_tunnel_set_runtime_dir(path *C.char) {
	providerMu.Lock()
	runtimeDir = C.GoString(path)
	providerMu.Unlock()
}

//export camera_tunnel_call
func camera_tunnel_call(operation *C.char, requestJSON *C.char) (result *C.char) {
	defer func() {
		if recover() != nil {
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
	providerMu.Lock()
	defer providerMu.Unlock()
	if runtimeDir == "" {
		return C.CString(encodeResult(failure("native_unavailable")))
	}
	if !validInstanceID(request.InstanceID) {
		return C.CString(encodeResult(failure("invalid_request")))
	}
	providerKey := request.Provider + "\x00" + request.InstanceID
	provider, ok := providers[providerKey]
	if !ok {
		switch request.Provider {
		case "tailscale":
			provider = newTailscaleProvider(runtimeDir)
		case "zrok":
			provider = newZrokProvider(runtimeDir)
		default:
			return C.CString(encodeResult(failure("invalid_provider")))
		}
		providers[providerKey] = provider
	}
	var output tunnelResult
	switch C.GoString(operation) {
	case "authorize":
		output = provider.Authorize(request.Name, request.Credential)
	case "status":
		output = provider.Status(request.Name)
	case "start":
		output = provider.Start(request.Name, request.LocalPort, request.Public, request.PrivateProxyCredential)
	case "stop":
		output = provider.Stop()
	case "close":
		output = provider.Stop()
		delete(providers, providerKey)
	case "revoke":
		output = provider.Revoke(request.Name)
	default:
		output = failure("invalid_request")
	}
	return C.CString(encodeResult(output))
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
