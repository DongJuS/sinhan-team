CREATE TABLE IF NOT EXISTS plan_runs(id bigserial PRIMARY KEY,request_id text NOT NULL,result jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS evidence(source text PRIMARY KEY,content text NOT NULL,embedding double precision[],model text NOT NULL);
