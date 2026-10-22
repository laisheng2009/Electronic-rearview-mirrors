package main

import (
	"testing"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

func TestFunnelPermissionKeepsSDKReason(t *testing.T) {
	node := &ipnstate.PeerStatus{CapMap: tailcfg.NodeCapMap{tailcfg.CapabilityHTTPS: nil}}
	err := ipn.NodeCanFunnel(node)
	if err == nil {
		t.Fatal("SDK must reject a node without funnel capability")
	}
	if code := funnelFailureCode(err); code != "tailscale_funnel_not_allowed" {
		t.Fatalf("wrong result: %s", code)
	}
	delete(node.CapMap, tailcfg.CapabilityHTTPS)
	if code := funnelFailureCode(ipn.NodeCanFunnel(node)); code != "tailscale_funnel_failed" {
		t.Fatalf("HTTPS failure misclassified: %s", code)
	}
	node.CapMap[tailcfg.CapabilityHTTPS] = nil
	node.CapMap[tailcfg.NodeAttrFunnel] = nil
	if err := ipn.NodeCanFunnel(node); err != nil {
		t.Fatal(err)
	}
}
