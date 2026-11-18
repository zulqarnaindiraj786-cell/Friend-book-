# Cloudflare production readiness check

This marker file schedules a production Worker redeploy and read-only D1 diagnostics from the deployment workflow. The diagnostics inspect only aggregate resource counts and newest document IDs/owners; they do not copy, mutate, or delete Firebase or D1 user data. This rerun also checks Workers KV namespace provisioning with the updated Cloudflare API token scope.
