CREATE TABLE wallets(user_id text NOT NULL,method text NOT NULL,balance_krw int NOT NULL CHECK(balance_krw>=0),PRIMARY KEY(user_id,method));
INSERT INTO wallets SELECT u,m,500000 FROM unnest(ARRAY['00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002'])u CROSS JOIN unnest(ARRAY['CARD_A','CARD_B','LOCAL'])m;
CREATE TABLE transactions(execution_id text PRIMARY KEY,user_id text NOT NULL,method text NOT NULL,amount_krw int NOT NULL CHECK(amount_krw>=0),digest text NOT NULL,status text NOT NULL CHECK(status IN ('PAID','REFUNDED')),created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ledger(id bigserial PRIMARY KEY,execution_id text NOT NULL REFERENCES transactions(execution_id),kind text NOT NULL CHECK(kind IN ('CHARGE','REFUND')),amount_krw int NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(execution_id,kind));
CREATE TABLE faults(name text PRIMARY KEY,remaining int NOT NULL CHECK(remaining>=0));
