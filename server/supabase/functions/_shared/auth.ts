/**
 * 호출자가 서비스 롤인지 본다. 서명 검사는 Supabase 게이트웨이(verify_jwt)가 이미 하므로 여기서는 역할만 읽는다.
 * 배포 환경의 SUPABASE_SERVICE_ROLE_KEY 는 시험용 키와 형식이 달라 문자열 비교를 쓸 수 없다(2026-10-23 실측).
 */
export function isServiceRoleBearer(header: string | null): boolean {
  const jwt = header?.match(/^Bearer\s+(\S+)$/)?.[1];
  const payload = jwt?.split(".")[1];
  if (!payload) return false;
  try {
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(payload.length / 4) * 4, "="));
    return JSON.parse(json)?.role === "service_role";
  } catch {
    return false;
  }
}
