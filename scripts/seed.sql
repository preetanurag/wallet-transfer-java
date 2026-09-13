-- Offline demo funding only. Re-running never resets existing balances.
INSERT INTO wallets(id,user_id,balance_paise) VALUES
  ('00000000-0000-4000-8000-000000000001','alice',100000),
  ('00000000-0000-4000-8000-000000000002','bob',100000),
  ('00000000-0000-4000-8000-000000000003','carol',100000)
ON CONFLICT(user_id) DO NOTHING;
