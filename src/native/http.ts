import axios from 'axios';

/**
 * Lightweight UI-side HTTP client (Axios).
 *
 * The *reliable* webhook delivery path is native (OkHttp + WorkManager) so it survives the JS
 * runtime being killed. This client is only for interactive, foreground checks initiated from the
 * UI — e.g. a quick reachability probe before saving a webhook.
 */
export const http = axios.create({ timeout: 10000 });

export interface PingResult {
  ok: boolean;
  status: number;
  ms: number;
  error?: string;
}

/** Probe a URL from the device to confirm it is reachable (no auth, no payload). */
export async function pingUrl(url: string): Promise<PingResult> {
  const started = Date.now();
  try {
    const res = await http.request({ url, method: 'GET', validateStatus: () => true });
    return { ok: res.status < 500, status: res.status, ms: Date.now() - started };
  } catch (e: any) {
    return { ok: false, status: 0, ms: Date.now() - started, error: e?.message ?? 'unreachable' };
  }
}
