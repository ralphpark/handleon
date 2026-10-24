-- "바로 공유" 알림이 1분 안에 가도록 발송 작업을 매분 돌린다(사용자 결정 2026-10-24).
-- 같은 행은 claim_token 으로 한 번만 가져가고, 실패 재시도 간격(10분)은 dispatch-store 가 따로 정한다.
-- 호출 수: 월 약 43,200번 — Edge Function 무료 한도(월 50만)의 약 9%.
select cron.alter_job(
  job_id := (select jobid from cron.job where jobname = 'handleon-dispatch-notifications'),
  schedule := '* * * * *'
);
