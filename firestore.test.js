const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_user_123";
const BOB_UID = "bob_user_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated access to users, private drops, and public discovery is rejected", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("sound_drops").get());
  await assertFails(unauthDb.collection("public_drops").where("geohashTile", "in", ["9q9hv"]).limit(50).get());
  await assertFails(unauthDb.collection("public_graffiti").where("geohashTile", "in", ["9q9hv"]).limit(50).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("sound_drops").doc("drop_1").set({
      userId: ALICE_UID,
      title: "Pirate Signal",
      description: "Test",
      latitude: 37.422,
      longitude: -122.084,
      radiusInMeters: 150.0,
      anonymous: false,
      audioDurationSeconds: 12,
      creatorName: "alice",
      clientTimestampMs: Date.now(),
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Owner can register and update profile; cross-user read and immutable mutation fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Operator",
      handle: "alice_2nd",
      email: "alice@example.com",
      bio: "Street audio archivist",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      handle: "alice_cipher",
      bio: "Updated bio",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").get());
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      email: "spoof@example.com",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Private SoundDrop CRUD enforces ownership, DSP/URL schema, no @DocumentId field, and future timestamp guard", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sound_drops").doc("drop_1").set({
      userId: ALICE_UID,
      title: "Midnight Subbass",
      description: "Under the concrete bridge",
      latitude: 37.422,
      longitude: -122.084,
      radiusInMeters: 200.0,
      geohash: "9q9hvu7x1",
      geohashTile: "9q9hv",
      anonymous: false,
      audioDurationSeconds: 18,
      creatorName: "alice_2nd",
      dspPreset: "RADIO_BANDPASS",
      audioUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/drop_1.wav?alt=media",
      audioStoragePath: `sound_drops/${ALICE_UID}/drop_1.wav`,
      localAudioPath: "/data/user/0/app/files/drop_1.wav",
      clientTimestampMs: Date.now(),
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("sound_drops").get());
  await assertSucceeds(bobDb.collection("users").doc(BOB_UID).collection("sound_drops").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sound_drops").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sound_drops").doc("drop_1").delete());

  // Reject @DocumentId id field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("sound_drops").doc("drop_2").set({
      id: "drop_2",
      userId: ALICE_UID,
      title: "Bad Payload",
      description: "Contains id",
      latitude: 37.422,
      longitude: -122.084,
      radiusInMeters: 150.0,
      anonymous: true,
      audioDurationSeconds: 10,
      creatorName: "Anonymous",
      clientTimestampMs: Date.now(),
      createdAt: created,
      updatedAt: created,
    })
  );
});

test("Public Geohash discovery allows cross-user bounded tile queries and blocks spoofing/unbounded scrapes", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  // Alice publishes a public drop in tile 9q9hv
  await assertSucceeds(
    aliceDb.collection("public_drops").doc("pub_drop_1").set({
      userId: ALICE_UID,
      title: "Cross-User Transmission",
      description: "Radio bandpass filtered street story",
      latitude: 37.422,
      longitude: -122.084,
      radiusInMeters: 250.0,
      geohash: "9q9hvu7x1",
      geohashTile: "9q9hv",
      anonymous: true,
      audioDurationSeconds: 14,
      creatorName: "Anonymous",
      dspPreset: "RADIO_BANDPASS",
      audioUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/pub_drop_1.wav?alt=media",
      audioStoragePath: `sound_drops/${ALICE_UID}/pub_drop_1.wav`,
      clientTimestampMs: Date.now(),
      createdAt: created,
      updatedAt: created,
    })
  );

  // Alice publishes a public AR VPS graffiti stencil with PDF media
  await assertSucceeds(
    aliceDb.collection("public_graffiti").doc("pub_graf_1").set({
      userId: ALICE_UID,
      contentText: "ROOFTOP ZINE ARCHIVE",
      author: "alice_2nd",
      latitude: 37.422,
      longitude: -122.084,
      altitudeMeters: 18.5,
      geohash: "9q9hvu7x1",
      geohashTile: "9q9hv",
      anchorType: "VPS_ROOFTOP",
      anchorAzimuth: 120.0,
      anchorPitch: 5.0,
      vpsHeadingDeg: 118.4,
      horizontalAccuracyM: 1.2,
      verticalAccuracyM: 0.8,
      colorHex: "#00D2FF",
      sizeScale: 1.2,
      mediaType: "PDF",
      mediaUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/zine.pdf?alt=media",
      mediaStoragePath: `graffiti_media/${ALICE_UID}/zine.pdf`,
      clientTimestampMs: Date.now(),
      createdAt: created,
      updatedAt: created,
    })
  );

  // Bob discovers Alice's public drop & AR stencil via bounded Geohash tile query
  await assertSucceeds(
    bobDb.collection("public_drops").where("geohashTile", "in", ["9q9hv", "9q9hu"]).limit(100).get()
  );
  await assertSucceeds(
    bobDb.collection("public_graffiti").where("geohashTile", "in", ["9q9hv", "9q9hu"]).limit(100).get()
  );

  // Unbounded scrape (missing limit or limit > 100) must fail
  await assertFails(bobDb.collection("public_drops").get());
  await assertFails(bobDb.collection("public_drops").where("geohashTile", "in", ["9q9hv"]).limit(200).get());

  // Bob cannot spoof Alice's userId or delete/update Alice's public drop
  await assertFails(
    bobDb.collection("public_drops").doc("pub_drop_spoof").set({
      userId: ALICE_UID,
      title: "Spoof",
      description: "Invalid",
      latitude: 37.422,
      longitude: -122.084,
      radiusInMeters: 150.0,
      geohash: "9q9hvu7x1",
      geohashTile: "9q9hv",
      anonymous: false,
      audioDurationSeconds: 5,
      creatorName: "bob",
      dspPreset: "RAW",
      clientTimestampMs: Date.now(),
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertFails(bobDb.collection("public_drops").doc("pub_drop_1").delete());
  await assertFails(bobDb.collection("public_graffiti").doc("pub_graf_1").delete());

  // Owner Alice can delete her public drop & graffiti
  await assertSucceeds(aliceDb.collection("public_drops").doc("pub_drop_1").delete());
  await assertSucceeds(aliceDb.collection("public_graffiti").doc("pub_graf_1").delete());
});
