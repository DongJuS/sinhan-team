CREATE TABLE requests(id text PRIMARY KEY,user_id text NOT NULL,idempotency_key text NOT NULL,input_hash text NOT NULL,payload jsonb NOT NULL,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(user_id,idempotency_key));
CREATE TABLE proposals(id text PRIMARY KEY,request_id text NOT NULL REFERENCES requests(id),payload jsonb NOT NULL);
CREATE TABLE jobs(id text PRIMARY KEY,request_id text NOT NULL REFERENCES requests(id),kind text NOT NULL,status text NOT NULL,owner text,lease_until timestamptz,attempts int NOT NULL DEFAULT 0,available_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX jobs_pending ON jobs(status,available_at);
