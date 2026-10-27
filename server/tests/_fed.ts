import type { FedStore, FedUpdate } from "../supabase/functions/_shared/fedavg.ts";
import { assertAlmostEquals, assertEquals } from "jsr:@std/assert@1";

type Client = { driver_id: string; client_tag: string };
type Snapshot = {
  round: number;
  weights: number[];
  input_hash: string;
  updates: (FedUpdate & Client)[];
};

export function assertFedWeights(actual: number[], expected: number[]) {
  assertEquals(actual.length, expected.length);
  expected.forEach((value, i) => assertAlmostEquals(actual[i], value, 1e-12));
}

// Test-only boundary: never publish other development clients' submissions.
export function testFedRound(source: FedStore, round: number, clients: readonly Client[]) {
  const identities = (values: readonly Client[]) =>
    values.map(({ driver_id, client_tag }) => `${driver_id}:${client_tag}`).sort();
  const expected = JSON.stringify(identities(clients));
  return {
    ...source,
    snapshot: async (): Promise<Snapshot> => {
      const value = await source.snapshot() as Snapshot;
      if (
        value?.round !== round || !Array.isArray(value?.updates) ||
        JSON.stringify(identities(value.updates)) !== expected
      ) throw new Error("test_requires_isolated_federated_round");
      // Return the SAME complete snapshot and hash, never filter foreign rows.
      // Submissions after this read are rejected by publish_fed_round's hash check.
      return value;
    },
  };
}
