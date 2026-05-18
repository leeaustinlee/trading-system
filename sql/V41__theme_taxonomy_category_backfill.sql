-- W2-2 Theme taxonomy category backfill.
-- Data-quality only: fills missing taxonomy labels from theme_tag so observability
-- can distinguish real category coverage from UNKNOWN/UNCATEGORIZED noise.
-- Does not alter candidate ranking, BUY/SELL/FinalDecision semantics, risk gates,
-- price gates, capital sizing, or Theme Live Decision behavior.

UPDATE stock_theme_mapping
   SET theme_category = CASE
       WHEN theme_tag IS NULL OR TRIM(theme_tag) = '' THEN 'UNKNOWN'
       WHEN UPPER(theme_tag) LIKE 'AI_CHIP%' OR theme_tag REGEXP 'AI伺服器|AI算力|GB200|伺服器|電腦週邊' THEN 'AI_COMPUTE'
       WHEN theme_tag REGEXP 'PCB|載板|材料|銅箔|CCL|ABF' THEN 'PCB'
       WHEN theme_tag REGEXP '記憶體|儲存|DRAM|NAND|SSD' THEN 'MEMORY'
       WHEN theme_tag REGEXP '半導體|IC|晶圓|封測|ASIC' THEN 'SEMICONDUCTOR'
       WHEN theme_tag REGEXP '散熱|機構|水冷|熱管|風扇' THEN 'COOLING'
       WHEN theme_tag REGEXP '網通|通訊|5G|交換器|光通訊' THEN 'COMMUNICATION'
       WHEN theme_tag REGEXP '機器人|自動化' THEN 'ROBOTICS'
       WHEN theme_tag REGEXP '光電|面板|MiniLED|MicroLED' THEN 'DISPLAY'
       WHEN theme_tag REGEXP '金融|銀行|保險|證券' THEN 'FINANCIAL'
       WHEN theme_tag REGEXP '玻纖|玻璃|原物料|化工|塑化' THEN 'MATERIALS'
       WHEN theme_tag REGEXP '軍工|航太|無人機' THEN 'DEFENSE'
       WHEN theme_tag REGEXP '生技|醫療|製藥|藥' THEN 'BIOTECH'
       WHEN theme_tag REGEXP '其他|強勢股' THEN 'OTHER'
       ELSE 'OTHER'
   END
 WHERE theme_category IS NULL OR TRIM(theme_category) = '';

UPDATE theme_snapshot
   SET theme_category = CASE
       WHEN theme_tag IS NULL OR TRIM(theme_tag) = '' THEN 'UNKNOWN'
       WHEN UPPER(theme_tag) LIKE 'AI_CHIP%' OR theme_tag REGEXP 'AI伺服器|AI算力|GB200|伺服器|電腦週邊' THEN 'AI_COMPUTE'
       WHEN theme_tag REGEXP 'PCB|載板|材料|銅箔|CCL|ABF' THEN 'PCB'
       WHEN theme_tag REGEXP '記憶體|儲存|DRAM|NAND|SSD' THEN 'MEMORY'
       WHEN theme_tag REGEXP '半導體|IC|晶圓|封測|ASIC' THEN 'SEMICONDUCTOR'
       WHEN theme_tag REGEXP '散熱|機構|水冷|熱管|風扇' THEN 'COOLING'
       WHEN theme_tag REGEXP '網通|通訊|5G|交換器|光通訊' THEN 'COMMUNICATION'
       WHEN theme_tag REGEXP '機器人|自動化' THEN 'ROBOTICS'
       WHEN theme_tag REGEXP '光電|面板|MiniLED|MicroLED' THEN 'DISPLAY'
       WHEN theme_tag REGEXP '金融|銀行|保險|證券' THEN 'FINANCIAL'
       WHEN theme_tag REGEXP '玻纖|玻璃|原物料|化工|塑化' THEN 'MATERIALS'
       WHEN theme_tag REGEXP '軍工|航太|無人機' THEN 'DEFENSE'
       WHEN theme_tag REGEXP '生技|醫療|製藥|藥' THEN 'BIOTECH'
       WHEN theme_tag REGEXP '其他|強勢股' THEN 'OTHER'
       ELSE 'OTHER'
   END
 WHERE theme_category IS NULL OR TRIM(theme_category) = '';
