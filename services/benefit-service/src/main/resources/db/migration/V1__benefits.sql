CREATE TABLE rules(method text PRIMARY KEY, version text NOT NULL,instant_bps int NOT NULL,statement_bps int NOT NULL,cashback_bps int NOT NULL,points_bps int NOT NULL,point_numerator int NOT NULL,point_denominator int NOT NULL,per_order_cap int NOT NULL,monthly_cap int NOT NULL);
INSERT INTO rules VALUES ('CARD_A','2026-11-v1',1000,0,0,100,1,1,2500,5000),('CARD_B','2026-11-v1',0,500,1000,50,1,1,3000,6000),('LOCAL','2026-11-v1',0,0,500,200,1,1,2000,4000);
CREATE TABLE reservations(execution_id text PRIMARY KEY,user_id text NOT NULL,method text NOT NULL,value_krw int NOT NULL CHECK(value_krw>=0),digest text NOT NULL,period text NOT NULL,status text NOT NULL CHECK(status IN ('RESERVED','COMMITTED','RELEASED')));
CREATE INDEX reservations_usage ON reservations(user_id,method,period);
