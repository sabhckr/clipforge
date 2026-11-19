# Security

CLIPFORGE 1.0 is a **local, single-owner** application. By default the API listens only on
`127.0.0.1`.

## Built-in protections

| Area | Control |
|---|---|
| Upload validation | Extension allow-list is a first gate only. The **content** must pass FFprobe as MP4/MOV, Matroska/WebM or AVI, with a video stream and positive duration. Rejected uploads are deleted |
| Resource limits | Upload size cap (checked against Content-Length and again while streaming), queue cap (429), concurrent-worker cap, minimum free disk (507 and job stop), FFprobe timeout, export ZIP retention |
| Paths | All storage paths come from server-generated UUIDs and fixed names. Client filenames are sanitised and used for display only. Every resolved path is checked with `safe_join` to stay inside the data directory. Malformed IDs get 400 |
| Subprocesses | FFmpeg and FFprobe are invoked with argument lists (no shell). User values reach filter graphs only as validated numbers or enums |
| Cancellation / crashes | FFmpeg processes are terminated on cancel. Partial files are written as `.partial` and removed on failure |
| Output metadata | Source container metadata (including any GPS or device tags) and chapters are **stripped** from clips. The only tag added is `comment=CLIPFORGE asset_id=<uuid>`, which is documented and not secret |
| HTTP | CORS allow-list (`CLIPFORGE_CORS_ORIGINS`), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` |
| Launcher | `python -m clipforge` refuses to bind a non-loopback address unless `CLIPFORGE_API_TOKEN` is set |

There is no telemetry, analytics, fingerprinting or third-party network call. The only outbound
processes are the local `ffmpeg` and `ffprobe` executables.

## Signing keys

- On first start the Ed25519 key pair is generated in `CLIPFORGE_KEY_DIR` (default
  `data/keys/`). The private key file is created with `O_EXCL` and mode **0600**, inside a
  **0700** directory.
- No endpoint returns the private key, and the React app never receives it. Only the public key
  is served (`/api/provenance/key`, `/api/provenance/public_key.pem`) and put in exports.
- `data/` and `*.pem` are in `.gitignore`. **Never commit the key directory.**
- **Back up** `clipforge_ed25519_private.pem` somewhere secure, such as an encrypted volume or a
  password manager's file storage. If it is lost, new manifests get a new key, and old manifests
  still verify only against the old public key.
- **Rotation.** Stop the server, move the key directory aside, and start again to generate a new
  pair. Keep old public keys so you can verify older exports.
- To keep the key off the data volume, point `CLIPFORGE_KEY_DIR` at a separate protected
  location.

## What provenance does and does not prove

A valid report shows that:

1. The manifest has not changed since it was signed by the holder of the private key with the
   reported `key_id`.
2. Each checked file is byte-identical to the file recorded in the manifest (SHA-256), and you
   can see its parent source asset.

It does **not** prevent anyone from copying, re-encoding or re-uploading a clip. A re-encoded
copy has a different hash and shows up as `unknown`. It also does **not** establish copyright
ownership: signing only proves control of a key at signing time. Owner name and contact are
optional, owner-declared fields, off by default, and labelled "not independently verified" in
the manifest.

## Exposing CLIPFORGE beyond localhost

Do **not** expose the development servers (Vite on 5173, or Uvicorn bound to 0.0.0.0 without a
token) on a network. For private remote access, such as a phone or Windows PC on your network:

1. Build the UI (`npm run build`) so FastAPI serves UI and API from one origin.
2. Set a long random token: `export CLIPFORGE_API_TOKEN="$(openssl rand -base64 32)"`.
3. Put a TLS-terminating reverse proxy in front and keep CLIPFORGE on loopback. A Caddy example:

   ```
   clipforge.example.internal {
       reverse_proxy 127.0.0.1:8710
       request_body { max_size 8GB }
   }
   ```

   The session cookie is marked `Secure` automatically when the request scheme is HTTPS. If
   your proxy terminates TLS, either forward the scheme (and run Uvicorn with
   `--proxy-headers --forwarded-allow-ips 127.0.0.1`) or rely on the proxy for HTTPS only.
4. Prefer a private overlay network (Tailscale, WireGuard) or the proxy's own authentication
   (SSO, mutual TLS) over open internet exposure.
5. Set `CLIPFORGE_CORS_ORIGINS` to the exact HTTPS origin.

The token is a **single shared secret**. There are no per-user accounts, roles or audit logs.
Multi-user or public hosting would need a real identity system, per-user storage isolation,
rate limiting and a security review. Those are out of scope for 1.0.

## Reporting

This is a local tool. If you find a vulnerability, fix it locally or contact the maintainer of
your copy. Don't post exploit details publicly before a fix is available.
