import { useEffect, useState } from "react";

export type Request = <T>(path: string, method?: string, body?: unknown) => Promise<T>;
const prefix = "/api/v1/media/";

export async function mediaField(request: Request, values: FormData, name: string) {
  const file = values.get(name + "File");
  if (file instanceof File && file.size > 0) {
    const payload = new FormData();
    payload.set("file", file);
    return (await request<{ url: string }>("/media", "POST", payload)).url;
  }
  return String(values.get(name) ?? "");
}

export function MediaField({ name, label, value, image = true }: { name: string; label: string; value?: string | null; image?: boolean }) {
  const uploaded = value?.startsWith(prefix);
  const [remove, setRemove] = useState(false);
  return <div className="stack">
    {uploaded && !remove ? <>
      <input type="hidden" name={name} value={value ?? ""} />
      <div className="row spread"><small>{label} uploaded</small><button type="button" onClick={() => setRemove(true)}>Remove {label.toLowerCase()}</button></div>
    </> : <input name={name} aria-label={label + " URL"} placeholder={label + " URL (optional)"} type="url" defaultValue={uploaded ? "" : value ?? ""} maxLength={500} />}
    <label><small>{label} file (optional, up to 5 MiB)</small><input name={name + "File"} type="file" accept={image ? "image/jpeg,image/png" : "image/jpeg,image/png,application/pdf"} /></label>
  </div>;
}

function useMedia(url: string, request: Request, thumb: boolean) {
  const [file, setFile] = useState<{ url: string; source: string; blob: Blob } | null>(null);
  useEffect(() => {
    if (!url.startsWith(prefix)) return;
    let active = true;
    let object: string | undefined;
    request<Blob>(url.slice("/api/v1".length) + (thumb ? "?thumb=true" : "")).then(blob => {
      if (!active) return;
      object = URL.createObjectURL(blob);
      setFile({ url: object, source: url, blob });
    }, () => {});
    return () => { active = false; if (object) URL.revokeObjectURL(object); };
  }, [url, request, thumb]);
  return file?.source === url ? file : null;
}

export function MediaImage({ url, request, className, alt = "", thumb = false }: { url: string; request: Request; className?: string; alt?: string; thumb?: boolean }) {
  const file = useMedia(url, request, thumb);
  const source = url.startsWith(prefix) ? file?.url : url;
  return source ? <img src={source} className={className} alt={alt} referrerPolicy="no-referrer" /> : null;
}

export function Attachment({ url, request }: { url: string; request: Request }) {
  const file = useMedia(url, request, false);
  if (url.startsWith(prefix)) {
    return file ? <a href={file.url} download={file.blob.type === "application/pdf" ? "attachment.pdf" : "attachment.jpg"}>Download attachment</a> : <small>Loading attachment…</small>;
  }
  return <a href={url} rel="noreferrer noopener" target="_blank">Open attachment</a>;
}
