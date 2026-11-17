import { Router } from "express";
import { checkPassword, hashPassword } from "@/db/password";
import { getDb, nextId, save } from "@/db/store";
import type { SiteUser } from "@/db/types";
import { signToken } from "@/middleware/auth";
import { rateLimit } from "@/middleware/rateLimit";
import { SiteLogin, SiteRegister } from "@/schemas";

/** Landing foydalanuvchilari: ro'yxatdan o'tish va kirish. */
export const authRouter = Router();

export const publicUser = (u: SiteUser) => ({
  id: u.id,
  username: u.username,
  createdAt: u.createdAt,
});
const session = (u: SiteUser) => ({
  token: signToken("user", u.id),
  user: publicUser(u),
  favorites: u.favorites,
});

authRouter.post("/register", rateLimit(10, 60 * 60 * 1000), (req, res) => {
  const c = SiteRegister.parse(req.body ?? {});
  const db = getDb();
  if (
    db.users.some((u) => u.username.toLowerCase() === c.username.toLowerCase())
  )
    return res.status(409).json({ error: "username_taken" });
  const user: SiteUser = {
    id: nextId(db.users),
    username: c.username,
    hash: hashPassword(c.password),
    createdAt: new Date().toISOString(),
    blocked: false,
    favorites: [],
  };
  db.users.push(user);
  save();
  res.json(session(user));
});

authRouter.post("/login", rateLimit(20, 15 * 60 * 1000), (req, res) => {
  const c = SiteLogin.parse(req.body ?? {});
  const user = getDb().users.find(
    (u) => u.username.toLowerCase() === c.username.toLowerCase(),
  );
  if (!user || !checkPassword(c.password, user.hash))
    return res.status(401).json({ error: "invalid_credentials" });
  if (user.blocked) return res.status(403).json({ error: "blocked" });
  res.json(session(user));
});
