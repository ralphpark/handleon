import { assert, assertEquals } from "jsr:@std/assert@1";
import { admin, integrationTest as test, linkedPair, newCar, newUser, ok } from "./_client.ts";

const payload = JSON.stringify({ version: 1, source: "google_timeline", trips: [] });
test("driver uploads, linked car reads and deletes", async () => {
  const { driver, car } = await linkedPair();
  const path = `${driver.id}/${crypto.randomUUID()}.json`;
  try {
    ok(
      await driver.client.storage.from("imports").upload(path, payload, {
        contentType: "application/json",
      }),
    );
    const blob = ok(await car.client.storage.from("imports").download(path));
    assertEquals(await blob!.text(), payload);
    ok(await car.client.storage.from("imports").remove([path]));
    assertEquals(ok(await driver.client.storage.from("imports").list(driver.id)), []);
  } finally {
    ok(await admin.storage.from("imports").remove([path]));
  }
});
test("other linked or unlinked car cannot read or delete imports", async () => {
  const { driver } = await linkedPair();
  const unlinked = await newCar();
  const other = await linkedPair();
  const path = `${driver.id}/${crypto.randomUUID()}.json`;
  try {
    ok(
      await driver.client.storage.from("imports").upload(path, payload, {
        contentType: "application/json",
      }),
    );
    for (const actor of [unlinked, other.car]) {
      assert((await actor.client.storage.from("imports").download(path)).error);
      await actor.client.storage.from("imports").remove([path]);
      assert(ok(await driver.client.storage.from("imports").download(path)));
    }
  } finally {
    ok(await admin.storage.from("imports").remove([path]));
  }
});
test("family cannot upload even to its own folder and drivers cannot upload elsewhere", async () => {
  const family = await newUser("family");
  const { driver, car } = await linkedPair();
  const paths = [`${family.id}/test.json`, `${driver.id}/test.json`];
  try {
    assert(
      (await family.client.storage.from("imports").upload(paths[0], payload, {
        contentType: "application/json",
      })).error,
    );
    assert(
      (await driver.client.storage.from("imports").upload(paths[0], payload, {
        contentType: "application/json",
      })).error,
    );
    assert(
      (await car.client.storage.from("imports").upload(paths[1], payload, {
        contentType: "application/json",
      })).error,
    );
  } finally {
    ok(await admin.storage.from("imports").remove(paths));
  }
});
test("driver can read and delete own imports; MIME and 5 MB limit are enforced", async () => {
  const driver = await newUser("driver");
  const path = `${driver.id}/test.json`;
  try {
    assert(
      (await driver.client.storage.from("imports").upload(path, "not json", {
        contentType: "text/plain",
      })).error,
    );
    assert(
      (await driver.client.storage.from("imports").upload(path, new Uint8Array(5_242_881), {
        contentType: "application/json",
      })).error,
    );
    ok(
      await driver.client.storage.from("imports").upload(path, payload, {
        contentType: "application/json",
      }),
    );
    assertEquals(
      await ok(await driver.client.storage.from("imports").download(path))!.text(),
      payload,
    );
    ok(await driver.client.storage.from("imports").remove([path]));
  } finally {
    ok(await admin.storage.from("imports").remove([path]));
  }
});
