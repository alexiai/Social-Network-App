import { notFound } from "next/navigation";
import Dashboard from "../dashboard";
import { routes, viewForPath } from "../navigation";

type Props = { params: Promise<{ section: string }> };

export function generateStaticParams() {
  return Object.values(routes).filter(route => route !== "/").map(route => ({ section: route.slice(1) }));
}

export async function generateMetadata({ params }: Props) {
  const view = viewForPath("/" + (await params).section);
  return view ? { title: view + " | Soleil" } : {};
}

export default async function Section({ params }: Props) {
  const view = viewForPath("/" + (await params).section);
  if (!view) notFound();
  return <Dashboard initialView={view} />;
}
