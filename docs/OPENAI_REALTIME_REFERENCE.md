# OpenAI Realtime API Reference

This file stores the canonical documentation references used by the realtime client.
Last reviewed: 2026-02-12.

Primary references:
- https://developers.openai.com/api/docs/guides/realtime
- https://developers.openai.com/api/docs/guides/realtime#beta-to-ga-migration-guide
- https://developers.openai.com/api/reference/resources/realtime
- https://developers.openai.com/api/reference/resources/realtime/client-events#session-update

Notes:
- GA uses `output_modalities` and `max_output_tokens` in session updates.
- Session config uses `audio.input` / `audio.output` and `type: "realtime"`.
- Session creation uses `/v1/realtime/client_secrets`; SDP exchange uses `/v1/realtime/calls`.
- Realtime responses are either audio or text output, not both at once.
