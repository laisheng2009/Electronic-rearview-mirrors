package main

import "strings"

func funnelFailureCode(err error) string {
	if strings.Contains(err.Error(), `"funnel" node attribute not set`) {
		return "tailscale_funnel_not_allowed"
	}
	return "tailscale_funnel_failed"
}
