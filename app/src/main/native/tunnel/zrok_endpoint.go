package main

import (
	"fmt"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

var endpointDNSLabel = regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$`)

func firstHttpsEndpoint(endpoints []string) (address, diagnostic string) {
	forms := map[string]int{}
	selected, scheme := -1, "none"
	for index, endpoint := range endpoints {
		normalized, form := normalizeZrokEndpoint(endpoint)
		forms[form]++
		if address == "" && normalized != "" {
			address, selected, scheme = normalized, index, "https"
		}
	}
	// Only fixed categories and counts: hostnames can contain private share identifiers.
	diagnostic = fmt.Sprintf("endpoint_count=%d forms=%v selected_index=%d selected_scheme=%s",
		len(endpoints), forms, selected, scheme)
	return
}

func normalizeZrokEndpoint(endpoint string) (string, string) {
	value := strings.TrimSpace(endpoint)
	if value == "" {
		return "", "empty"
	}
	form := "https_url"
	if !strings.Contains(value, "://") {
		// Zrok v2 returns name.namespace, not a URL. Do not reinterpret paths or credentials.
		if !validEndpointDNSName(value) {
			return "", "invalid_hostname"
		}
		value, form = "https://"+value, "bare_hostname"
	}
	parsed, err := url.Parse(value)
	if err != nil {
		return "", "malformed_url"
	}
	if parsed.Scheme != "https" {
		return "", "unsupported_scheme"
	}
	if parsed.User != nil {
		return "", "userinfo"
	}
	if !validEndpointDNSName(parsed.Hostname()) && net.ParseIP(parsed.Hostname()) == nil {
		return "", "invalid_hostname"
	}
	if port := parsed.Port(); port != "" {
		number, err := strconv.Atoi(port)
		if err != nil || number < 1 || number > 65535 {
			return "", "invalid_port"
		}
	} else if strings.HasSuffix(parsed.Host, ":") {
		return "", "invalid_port"
	}
	return parsed.String(), form
}

func validEndpointDNSName(host string) bool {
	host = strings.TrimSuffix(host, ".")
	if len(host) == 0 || len(host) > 253 || !strings.Contains(host, ".") {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if !endpointDNSLabel.MatchString(label) {
			return false
		}
	}
	return true
}
