ALTER TABLE plan_runs ADD COLUMN runtime jsonb NOT NULL DEFAULT '{}';
ALTER TABLE plan_runs ADD COLUMN node_trace jsonb NOT NULL DEFAULT '[]';
