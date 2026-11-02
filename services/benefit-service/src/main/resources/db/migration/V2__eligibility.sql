ALTER TABLE rules ADD COLUMN required_previous_spend_krw int NOT NULL DEFAULT 0;
UPDATE rules SET required_previous_spend_krw=300000 WHERE method='CARD_A';
UPDATE rules SET required_previous_spend_krw=200000 WHERE method='CARD_B';
CREATE TABLE profiles(user_id text PRIMARY KEY,previous_month_spend_krw int NOT NULL CHECK(previous_month_spend_krw>=0));
INSERT INTO profiles VALUES('00000000-0000-0000-0000-000000000001',400000),('00000000-0000-0000-0000-000000000002',50000);
