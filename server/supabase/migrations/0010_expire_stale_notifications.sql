begin;
-- 받을 기기가 없거나 계속 실패해 7일이 지나도 못 보낸 알림은 하루 한 번 취소한다.
-- 발송 함수는 등록이 풀린 토큰(FCM 404)을 지우고, 보낼 기기가 없으면 10분마다 다시 시도하므로 끝을 정해 둔다.
select cron.schedule('handleon-expire-notifications', '23 3 * * *',
  $job$update public.notification_queue set cancelled_at = clock_timestamp()
    where sent_at is null and cancelled_at is null and due_at < now() - interval '7 days'$job$);
commit;
