# Cloudflare production readiness check

This marker file schedules a production Worker redeploy and read-only D1 diagnostics from the deployment workflow. The diagnostics inspect only aggregate resource counts and newest document IDs/owners; they do not copy, mutate, or delete Firebase or D1 user data. It also checks Workers KV namespace provisioning. The current user reports that feed, chat, friend requests, likes, and comments still fail, so inspect production D1 document-store counts and interaction readiness before claiming a fix.
