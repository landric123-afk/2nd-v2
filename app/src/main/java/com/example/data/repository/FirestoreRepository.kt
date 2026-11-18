package com.example.data.remote

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val docRef = db.collection("users").document(userId)
        emit(null)
        docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
                }
                throw error
            }
            .collect { emit(it) }
    }

    suspend fun ensureUserProfile(firebaseUser: FirebaseUser): Result<UserProfile> {
        val uid = firebaseUser.uid
        val docPath = "users/$uid"
        return try {
            val activeUid = requireUserId()
            check(activeUid == uid) { "Signed-in user mismatch" }
            val docRef = db.collection("users").document(uid)
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                val existing = snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                if (existing != null) return Result.success(existing)
            }

            val rawDisplay = firebaseUser.displayName?.trim().orEmpty().ifBlank {
                firebaseUser.email?.substringBefore("@")?.trim().orEmpty().ifBlank { "Operator 2ND" }
            }.take(50)
            val derivedHandle = rawDisplay
                .lowercase()
                .replace(Regex("[^a-z0-9_]"), "_")
                .trim('_')
                .ifBlank { "unit_${uid.take(6)}" }
                .take(30)
                .let { if (it.length < 2) "${it}_2nd" else it }

            val profile = UserProfile(
                id = uid,
                userId = uid,
                displayName = rawDisplay,
                handle = derivedHandle,
                email = (firebaseUser.email ?: "").take(120),
                bio = "2ND spatial audio & AR graffiti operator",
                photoUrl = firebaseUser.photoUrl?.toString()?.take(500)
            )
            docRef.set(profile.toCreateMap()).await()
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateUserProfile(
        userId: String,
        displayName: String,
        handle: String,
        bio: String
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user profile update" }
            val cleanName = displayName.trim().take(50)
            val cleanHandle = handle.trim().lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(30)
            val cleanBio = bio.trim().take(200)
            require(cleanName.isNotEmpty()) { "Display name cannot be empty" }
            require(cleanHandle.length >= 2) { "Handle must be at least 2 characters" }

            db.collection("users").document(uid)
                .update(
                    mapOf(
                        "displayName" to cleanName,
                        "handle" to cleanHandle,
                        "bio" to cleanBio,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeSoundDrops(userId: String): Flow<List<CloudSoundDrop>> {
        val path = "users/$userId/sound_drops"
        return db.collection("users").document(userId).collection("sound_drops")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CloudSoundDrop::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    /**
     * Streams public Sound-Drops left by other users inside the surrounding 3x3 Geohash tiles.
     * Uses `.whereIn("geohashTile", ...).limit(100)` aligned with firestore.rules.
     */
    fun observePublicSoundDrops(tiles: List<String>): Flow<List<CloudSoundDrop>> {
        val sanitizedTiles = tiles.filter { it.length == 5 }.distinct().take(9)
        if (sanitizedTiles.isEmpty() || auth.currentUser?.uid.isNullOrBlank()) {
            return flowOf(emptyList())
        }
        val path = "public_drops"
        return db.collection("public_drops")
            .whereIn("geohashTile", sanitizedTiles)
            .limit(100)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CloudSoundDrop::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun createSoundDrop(
        userId: String,
        drop: CloudSoundDrop,
        publishToPublicIndex: Boolean = true
    ): Result<String> {
        var docPath = "users/$userId/sound_drops"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            val privateRef = db.collection("users").document(uid).collection("sound_drops").document()
            docPath = privateRef.path
            val normalized = drop.copy(userId = uid)
            privateRef.set(normalized.toCreateMap()).await()

            if (publishToPublicIndex) {
                val publicRef = db.collection("public_drops").document(privateRef.id)
                publicRef.set(normalized.toPublicMap()).await()
            }
            Result.success(privateRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteSoundDrop(userId: String, dropId: String): Result<Unit> {
        val docPath = "users/$userId/sound_drops/$dropId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            require(dropId.isNotBlank()) { "Drop ID must not be blank" }
            db.collection("users").document(uid).collection("sound_drops").document(dropId).delete().await()
            try {
                db.collection("public_drops").document(dropId).delete().await()
            } catch (_: Exception) {
                // Public doc may already be removed or unpublished
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeVirtualGraffiti(userId: String): Flow<List<CloudVirtualGraffiti>> {
        val path = "users/$userId/virtual_graffiti"
        return db.collection("users").document(userId).collection("virtual_graffiti")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CloudVirtualGraffiti::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    /**
     * Streams public AR Virtual Graffiti anchors left by other users inside the 3x3 Geohash tile window.
     */
    fun observePublicVirtualGraffiti(tiles: List<String>): Flow<List<CloudVirtualGraffiti>> {
        val sanitizedTiles = tiles.filter { it.length == 5 }.distinct().take(9)
        if (sanitizedTiles.isEmpty() || auth.currentUser?.uid.isNullOrBlank()) {
            return flowOf(emptyList())
        }
        val path = "public_graffiti"
        return db.collection("public_graffiti")
            .whereIn("geohashTile", sanitizedTiles)
            .limit(100)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CloudVirtualGraffiti::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun createVirtualGraffiti(
        userId: String,
        graffiti: CloudVirtualGraffiti,
        publishToPublicIndex: Boolean = true
    ): Result<String> {
        var docPath = "users/$userId/virtual_graffiti"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            val privateRef = db.collection("users").document(uid).collection("virtual_graffiti").document()
            docPath = privateRef.path
            val payload = graffiti.copy(userId = uid).toCreateMap()
            privateRef.set(payload).await()

            if (publishToPublicIndex) {
                db.collection("public_graffiti").document(privateRef.id).set(payload).await()
            }
            Result.success(privateRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteVirtualGraffiti(userId: String, graffitiId: String): Result<Unit> {
        val docPath = "users/$userId/virtual_graffiti/$graffitiId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            require(graffitiId.isNotBlank()) { "Graffiti ID must not be blank" }
            db.collection("users").document(uid).collection("virtual_graffiti").document(graffitiId).delete().await()
            try {
                db.collection("public_graffiti").document(graffitiId).delete().await()
            } catch (_: Exception) {
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
