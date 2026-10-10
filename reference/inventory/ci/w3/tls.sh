#!/usr/bin/env bash
#
# A throwaway certificate authority and a server certificate signed by it, for
# the static HTTPS bundle server in the W3 checks. Valid 30 days, for
# localhost, 127.0.0.1 (the iOS simulator) and 10.0.2.2 (the Android emulator's
# name for the host). Nothing here is trusted anywhere until a check installs
# ca.pem into ITS OWN emulator or simulator.
#
#   ci/w3/tls.sh <dir>     -> <dir>/{ca.pem, server.pem, server.key}
set -euo pipefail
D="${1:?usage: $0 <dir>}"
mkdir -p "$D"
cd "$D"
cat > ca.cnf <<'CNF'
[req]
distinguished_name = dn
[dn]
[v3_ca]
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
CNF
cat > server.cnf <<'CNF'
[srv]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, IP:127.0.0.1, IP:10.0.2.2
authorityKeyIdentifier = keyid
subjectKeyIdentifier = hash
CNF
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 30 -keyout ca.key -out ca.pem \
  -subj "/CN=Keliver W3 CI test CA" -config ca.cnf -extensions v3_ca 2>/dev/null
openssl req -newkey rsa:2048 -nodes -sha256 -keyout server.key -out server.csr -subj "/CN=localhost" 2>/dev/null
openssl x509 -req -sha256 -days 30 -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -out server.pem -extfile server.cnf -extensions srv 2>/dev/null
# The CA's key is not needed again: remove it, so nothing can sign with it later.
rm -f ca.key server.csr ca.srl
openssl x509 -in server.pem -noout -subject -issuer -ext subjectAltName 2>/dev/null \
  || openssl x509 -in server.pem -noout -subject -issuer
