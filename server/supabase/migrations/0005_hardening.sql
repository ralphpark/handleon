begin;
-- 보안 점검(get_advisors) 반영:
-- valid_summary_json 은 순수 검사 함수라 권한 상승이 필요 없다.
alter function public.valid_summary_json(jsonb, jsonb) security invoker;
-- save_monthly_letter 는 Edge Function(서비스 롤)만 부른다.
revoke execute on function public.save_monthly_letter(uuid,uuid,date,timestamptz,text) from authenticated;
commit;
