# Security policy

## Supported versions

Only the latest released version receives security fixes.

## Reporting a vulnerability

Please report vulnerabilities privately via
[GitHub security advisories](../../security/advisories/new); do not open a
public issue. You should receive a response within a week.

## Scope notes

This library feeds **untrusted input** (media files) into a native decoder,
so memory-safety defects in the parsing path are security-relevant by
definition. The container parser is deliberately strict (malformed headers,
truncated files and non-monotonic seek tables fail with clean errors, and a
deterministic fuzz corpus runs in CI), but MACLib itself is upstream code:
issues that reproduce with the official Monkey's Audio tools will be
forwarded to the upstream author.
