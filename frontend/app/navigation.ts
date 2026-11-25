export const routes = {
  Feed: "/",
  People: "/people",
  Communities: "/communities",
  Messages: "/messages",
  Events: "/events",
  Notifications: "/notifications",
  Profile: "/profile",
} as const;

export type View = keyof typeof routes;

export function viewForPath(path: string): View | undefined {
  return (Object.keys(routes) as View[]).find(view => routes[view] === path);
}
