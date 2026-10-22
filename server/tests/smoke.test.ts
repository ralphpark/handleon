import { assert } from "jsr:@std/assert@1";
import { integrationTest, newUser } from "./_client.ts";
integrationTest("new driver has an authenticated user id", async () => {
  assert((await newUser("driver")).id);
});
