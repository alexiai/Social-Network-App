export type Session = { username: string; accessToken: string };

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export async function api<T>(path: string, method = "GET", body?: unknown, token?: string): Promise<T> {
  const headers: Record<string, string> = { "X-Campus-Request": "web" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined && !(body instanceof FormData)) headers["Content-Type"] = "application/json";
  const response = await fetch("/api/v1" + path, {
    method, headers, credentials: "same-origin", cache: "no-store",
    body: body === undefined || body instanceof FormData ? body : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(response.status, error?.fields?.join(". ") || error?.detail || `Request failed (${response.status})`);
  }
  const type = response.headers.get("Content-Type") ?? "";
  if (type.startsWith("image/") || type === "application/pdf") return await response.blob() as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

let refreshing: Promise<Session> | null = null;
export function refresh() {
  if (!refreshing) {
    const rotate = () => api<Session>("/auth/refresh", "POST");
    const locks = globalThis.navigator?.locks;
    const acrossTabs = async () => locks ? await locks.request("campus-refresh", rotate) : await rotate();
    refreshing = acrossTabs().finally(() => { refreshing = null; });
  }
  return refreshing;
}

export function query(values: Record<string, string | number | boolean | null>) {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(values)) {
    if (value !== null && value !== "") params.set(name, String(value));
  }
  return params.size ? "?" + params : "";
}
