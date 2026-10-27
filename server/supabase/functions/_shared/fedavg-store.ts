import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import type { FedResult, FedStore } from "./fedavg.ts";

export function createFedStore(db: SupabaseClient): FedStore {
  return {
    async snapshot() {
      // One JSON RPC response, not a paged table query: no silent 1,000-row truncation.
      const { data, error } = await db.rpc("fed_round_snapshot");
      if (error || !data) throw new Error("fed_read_failed");
      return data;
    },
    async publish(round, inputHash, weights) {
      const { data, error } = await db.rpc("publish_fed_round", {
        p_round: round,
        p_input_hash: inputHash,
        p_weights: weights,
      });
      if (
        error || !data || typeof data.advanced !== "boolean" || !Number.isInteger(data.round) ||
        data.round < 0 || !Number.isInteger(data.clients) || data.clients < 0
      ) throw new Error("fed_publish_failed");
      const reason: FedResult["reason"] = data.reason;
      if (
        data.advanced
          ? reason !== undefined
          : !["insufficient_clients", "round_changed", "inputs_changed"].includes(reason ?? "")
      ) throw new Error("fed_publish_failed");
      // Only status fields leave the service. Never return submitted deltas or identifiers.
      return {
        advanced: data.advanced,
        round: data.round,
        clients: data.clients,
        ...(reason ? { reason } : {}),
      };
    },
  };
}
