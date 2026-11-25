import type { Metadata } from "next";
import "./styles.css";

export const metadata: Metadata = {
  title: "Soleil | Find your people",
  icons: { icon: "/soleil/logo.jpeg" },
  description: "A community for students, conversations and events.",
};

export default function Layout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
