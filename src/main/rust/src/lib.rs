use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jint, jlong, jdouble, jsize};
use jni::JNIEnv;
use parking_lot::RwLock;
use rayon::prelude::*;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::sync::atomic::{AtomicUsize, Ordering};

const HEIGHT_SHARD_COUNT: usize = 32;
const TRANSPARENCY_SHARD_COUNT: usize = 64;
const MAX_TRANSPARENCY_PER_SHARD: usize = 256;
const MAX_HEIGHT_PER_SHARD: usize = 64;
const LRU_CLEAN_THRESHOLD: usize = 16;

struct LruCache<K, V> {
    map: hashbrown::HashMap<K, (V, u64)>,
    access_counter: u64,
}

impl<K: Eq + Hash + Clone, V> LruCache<K, V> {
    fn with_capacity(cap: usize) -> Self {
        Self {
            map: hashbrown::HashMap::with_capacity(cap),
            access_counter: 0,
        }
    }

    fn get(&mut self, key: &K) -> Option<&V> {
        self.access_counter += 1;
        if let Some(entry) = self.map.get_mut(key) {
            entry.1 = self.access_counter;
            Some(&entry.0)
        } else {
            None
        }
    }

    fn insert(&mut self, key: K, value: V) {
        self.access_counter += 1;
        self.map.insert(key, (value, self.access_counter));
    }

    fn len(&self) -> usize {
        self.map.len()
    }

    fn retain_lru(&mut self, max_size: usize) {
        if self.map.len() <= max_size {
            return;
        }

        let excess = self.map.len() - max_size;
        let mut entries: Vec<_> = self.map.iter().collect();
        entries.sort_by_key(|(_, (_, access))| *access);

        let keys_to_remove: Vec<K> = entries
            .iter()
            .take(excess)
            .map(|(k, _)| (*k).clone())
            .collect();

        for key in keys_to_remove {
            self.map.remove(&key);
        }
    }

    fn clear(&mut self) {
        self.map.clear();
    }
}

type HeightShard = RwLock<LruCache<u64, i32>>;
type TransparencyShard = RwLock<LruCache<u64, bool>>;

struct ShardedHeightCache {
    shards: Vec<HeightShard>,
    access_counts: Vec<AtomicUsize>,
}

impl ShardedHeightCache {
    fn new() -> Self {
        Self {
            shards: (0..HEIGHT_SHARD_COUNT)
                .map(|_| RwLock::new(LruCache::with_capacity(64)))
                .collect(),
            access_counts: (0..HEIGHT_SHARD_COUNT)
                .map(|_| AtomicUsize::new(0))
                .collect(),
        }
    }

    fn shard_index(&self, key: u64) -> usize {
        let mut hasher = DefaultHasher::new();
        key.hash(&mut hasher);
        hasher.finish() as usize % HEIGHT_SHARD_COUNT
    }

    fn get(&self, key: u64) -> Option<i32> {
        let idx = self.shard_index(key);
        self.access_counts[idx].fetch_add(1, Ordering::Relaxed);
        self.shards[idx].write().get(&key).copied()
    }

    fn insert(&self, key: u64, value: i32) {
        let idx = self.shard_index(key);
        self.shards[idx].write().insert(key, value);
    }

    fn cleanup_around(&self, player_chunk_x: i32, player_chunk_z: i32) {
        for (idx, shard) in self.shards.iter().enumerate() {
            let access_count = self.access_counts[idx].swap(0, Ordering::Relaxed);
            
            let mut cache = shard.write();
            
            if access_count < 10 {
                cache.retain_lru(MAX_HEIGHT_PER_SHARD / 2);
            }

            if cache.len() > MAX_HEIGHT_PER_SHARD {
                let px = player_chunk_x;
                let pz = player_chunk_z;
                cache.map.retain(|&key, _| {
                    let chunk_x = (key >> 32) as i32;
                    let chunk_z = key as i32;
                    let dist = (chunk_x - px).abs() + (chunk_z - pz).abs();
                    dist <= 32
                });
            }
        }
    }

    fn clear(&self) {
        for shard in &self.shards {
            shard.write().clear();
        }
    }
}

struct ShardedTransparencyCache {
    shards: Vec<TransparencyShard>,
    access_counts: Vec<AtomicUsize>,
}

impl ShardedTransparencyCache {
    fn new() -> Self {
        Self {
            shards: (0..TRANSPARENCY_SHARD_COUNT)
                .map(|_| RwLock::new(LruCache::with_capacity(256)))
                .collect(),
            access_counts: (0..TRANSPARENCY_SHARD_COUNT)
                .map(|_| AtomicUsize::new(0))
                .collect(),
        }
    }

    fn shard_index(&self, key: u64) -> usize {
        let mut hasher = DefaultHasher::new();
        key.hash(&mut hasher);
        hasher.finish() as usize % TRANSPARENCY_SHARD_COUNT
    }

    fn get(&self, key: u64) -> Option<bool> {
        let idx = self.shard_index(key);
        self.access_counts[idx].fetch_add(1, Ordering::Relaxed);
        self.shards[idx].write().get(&key).copied()
    }

    fn insert(&self, key: u64, value: bool) {
        let idx = self.shard_index(key);
        let mut shard = self.shards[idx].write();
        shard.insert(key, value);
        
        if shard.len() > MAX_TRANSPARENCY_PER_SHARD {
            shard.retain_lru(MAX_TRANSPARENCY_PER_SHARD - LRU_CLEAN_THRESHOLD);
        }
    }

    fn clear(&self) {
        for shard in &self.shards {
            shard.write().clear();
        }
    }
}

static HEIGHT_CACHE: once_cell::sync::Lazy<ShardedHeightCache> = 
    once_cell::sync::Lazy::new(ShardedHeightCache::new);

static TRANSPARENCY_CACHE: once_cell::sync::Lazy<ShardedTransparencyCache> = 
    once_cell::sync::Lazy::new(ShardedTransparencyCache::new);

struct FrameState {
    cam_x: f64,
    cam_y: f64,
    cam_z: f64,
    look_x: f64,
    look_y: f64,
    look_z: f64,
    fov_cos_threshold: f64,
    is_underground: bool,
    is_nether: bool,
    has_ceiling: bool,
    reserved_height: i32,
    fov_enabled: bool,
    mountain_enabled: bool,
    vertical_enabled: bool,
    aggressive_vertical: bool,
    underground_horizontal: bool,
    underground_horizontal_dist: i32,
}

impl Default for FrameState {
    fn default() -> Self {
        Self {
            cam_x: 0.0,
            cam_y: 0.0,
            cam_z: 0.0,
            look_x: 0.0,
            look_y: 0.0,
            look_z: 1.0,
            fov_cos_threshold: 0.0,
            is_underground: false,
            is_nether: false,
            has_ceiling: false,
            reserved_height: 16,
            fov_enabled: true,
            mountain_enabled: true,
            vertical_enabled: true,
            aggressive_vertical: false,
            underground_horizontal: true,
            underground_horizontal_dist: 4,
        }
    }
}

static FRAME_STATE: once_cell::sync::Lazy<RwLock<FrameState>> = 
    once_cell::sync::Lazy::new(|| RwLock::new(FrameState::default()));

#[inline]
fn compute_should_cull(
    state: &FrameState,
    min_x: f64,
    min_y: f64,
    min_z: f64,
    max_y: f64,
    chunk_x: i32,
    chunk_z: i32,
) -> jint {
    if state.fov_enabled {
        let center_x = min_x + 8.0;
        let center_y = min_y + 8.0;
        let center_z = min_z + 8.0;

        let dx = center_x - state.cam_x;
        let dy = center_y - state.cam_y;
        let dz = center_z - state.cam_z;
        let dist_sq = dx * dx + dy * dy + dz * dz;

        let min_fov_dist_sq = if state.is_underground { 256.0 } else { 400.0 };

        if dist_sq > min_fov_dist_sq {
            let inv_dist = 1.0 / dist_sq.sqrt();
            let dot = (state.look_x * dx + state.look_y * dy + state.look_z * dz) * inv_dist;
            if dot < state.fov_cos_threshold {
                return 1;
            }
        }
    }

    if state.underground_horizontal && state.is_underground && !state.is_nether {
        let cam_chunk_x = (state.cam_x as i32) >> 4;
        let cam_chunk_z = (state.cam_z as i32) >> 4;
        let dx = (chunk_x - cam_chunk_x).abs();
        let dz = (chunk_z - cam_chunk_z).abs();

        if dx > state.underground_horizontal_dist || dz > state.underground_horizontal_dist {
            let center_x = min_x + 8.0;
            let center_z = min_z + 8.0;
            let target_dx = center_x - state.cam_x;
            let target_dz = center_z - state.cam_z;
            let dist_h_sq = target_dx * target_dx + target_dz * target_dz;

            if dist_h_sq > 1024.0 {
                let inv_dist_h = 1.0 / dist_h_sq.sqrt();
                let dot_h = (state.look_x * target_dx + state.look_z * target_dz) * inv_dist_h;
                if dot_h < 0.5 {
                    return 4;
                }
            }
        }
    }

    let key = ((chunk_x as u64) << 32) | ((chunk_z as u32) as u64);
    let surface_y = HEIGHT_CACHE.get(key).unwrap_or(-1);

    if surface_y != -1 {
        let surface_y_f = surface_y as f64;

        if state.mountain_enabled {
            if state.is_nether || (state.has_ceiling && state.cam_y < 120.0) {
                if state.cam_y < 110.0 && min_y > 128.0 {
                    return 2;
                }
            } else if state.is_underground {
                if min_y > surface_y_f + 8.0 {
                    let dx = (min_x + 8.0) - state.cam_x;
                    let dz = (min_z + 8.0) - state.cam_z;
                    if dx * dx + dz * dz > 1024.0 {
                        let dy = (min_y + 8.0) - state.cam_y;
                        let dist = (dx * dx + dy * dy + dz * dz).sqrt();
                        if dist > 0.1 {
                            let dot =
                                (state.look_x * dx + state.look_y * dy + state.look_z * dz) / dist;
                            if dot < state.fov_cos_threshold {
                                return 2;
                            }
                        }
                    }
                }
            }
        }

        if state.vertical_enabled {
            if state.is_nether {
                let diff_y = (min_y + 8.0 - state.cam_y).abs();
                if diff_y > (state.reserved_height + 32) as f64 {
                    return 3;
                }
            } else if !state.is_underground {
                let vertical_offset = if state.aggressive_vertical { 32.0 } else { 64.0 };
                if max_y < surface_y_f - vertical_offset && max_y < state.cam_y - vertical_offset {
                    return 3;
                }
            } else {
                let underground_offset = if state.aggressive_vertical {
                    state.reserved_height / 2
                } else {
                    state.reserved_height
                };
                let diff_y = (min_y + 8.0 - state.cam_y).abs();
                if diff_y > underground_offset as f64 {
                    let dx = (min_x + 8.0) - state.cam_x;
                    let dz = (min_z + 8.0) - state.cam_z;
                    let dy = (min_y + 8.0) - state.cam_y;
                    let dist = (dx * dx + dy * dy + dz * dz).sqrt();
                    if dist > 0.1 {
                        let dot =
                            (state.look_x * dx + state.look_y * dy + state.look_z * dz) / dist;
                        if dot < state.fov_cos_threshold {
                            return 3;
                        }
                    }
                }

                let surface_offset = if state.aggressive_vertical { -8.0 } else { 0.0 };
                if state.cam_y < surface_y_f - 16.0 && min_y > surface_y_f + surface_offset {
                    return 3;
                }
            }
        }
    }

    0
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeResetCache(
    _env: JNIEnv,
    _class: JClass,
) {
    HEIGHT_CACHE.clear();
    TRANSPARENCY_CACHE.clear();
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeGetCachedHeight(
    _env: JNIEnv,
    _class: JClass,
    key: jlong,
) -> jint {
    HEIGHT_CACHE.get(key as u64).unwrap_or(-1)
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativePutCachedHeight(
    _env: JNIEnv,
    _class: JClass,
    key: jlong,
    value: jint,
) {
    HEIGHT_CACHE.insert(key as u64, value);
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeGetCachedTransparency(
    _env: JNIEnv,
    _class: JClass,
    key: jlong,
) -> jint {
    match TRANSPARENCY_CACHE.get(key as u64) {
        Some(true) => 1,
        Some(false) => 0,
        None => -1,
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativePutCachedTransparency(
    _env: JNIEnv,
    _class: JClass,
    key: jlong,
    value: jboolean,
) {
    TRANSPARENCY_CACHE.insert(key as u64, value != 0);
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeCleanupCaches(
    _env: JNIEnv,
    _class: JClass,
    player_chunk_x: jint,
    player_chunk_z: jint,
) {
    HEIGHT_CACHE.cleanup_around(player_chunk_x, player_chunk_z);
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeUpdateFrameState(
    _env: JNIEnv,
    _class: JClass,
    camX: jdouble,
    camY: jdouble,
    camZ: jdouble,
    lookX: jdouble,
    lookY: jdouble,
    lookZ: jdouble,
    fovCos: jdouble,
    isUnderground: jboolean,
    isNether: jboolean,
    hasCeiling: jboolean,
    reservedHeight: jint,
    fovEnabled: jboolean,
    mountainEnabled: jboolean,
    verticalEnabled: jboolean,
    aggressiveVertical: jboolean,
    undergroundHorizontal: jboolean,
    undergroundHorizontalDist: jint,
) {
    let mut state = FRAME_STATE.write();
    state.cam_x = camX;
    state.cam_y = camY;
    state.cam_z = camZ;
    state.look_x = lookX;
    state.look_y = lookY;
    state.look_z = lookZ;
    state.fov_cos_threshold = fovCos;
    state.is_underground = isUnderground != 0;
    state.is_nether = isNether != 0;
    state.has_ceiling = hasCeiling != 0;
    state.reserved_height = reservedHeight;
    state.fov_enabled = fovEnabled != 0;
    state.mountain_enabled = mountainEnabled != 0;
    state.vertical_enabled = verticalEnabled != 0;
    state.aggressive_vertical = aggressiveVertical != 0;
    state.underground_horizontal = undergroundHorizontal != 0;
    state.underground_horizontal_dist = undergroundHorizontalDist;
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeShouldCull(
    _env: JNIEnv,
    _class: JClass,
    minX: jdouble,
    minY: jdouble,
    minZ: jdouble,
    _maxX: jdouble,
    maxY: jdouble,
    _maxZ: jdouble,
    chunkX: jint,
    chunkZ: jint,
) -> jint {
    let state = FRAME_STATE.read();
    compute_should_cull(
        &state,
        minX,
        minY,
        minZ,
        maxY,
        chunkX,
        chunkZ,
    )
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeShouldCullBatch<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    min_xs: JIntArray<'local>,
    min_ys: JIntArray<'local>,
    min_zs: JIntArray<'local>,
    max_ys: JIntArray<'local>,
    chunk_xs: JIntArray<'local>,
    chunk_zs: JIntArray<'local>,
    count: jint,
) -> JIntArray<'local> {
    let count = count as usize;
    if count == 0 {
        return env.new_int_array(0).unwrap();
    }

    let mut min_xs_vec = vec![0i32; count];
    let mut min_ys_vec = vec![0i32; count];
    let mut min_zs_vec = vec![0i32; count];
    let mut max_ys_vec = vec![0i32; count];
    let mut chunk_xs_vec = vec![0i32; count];
    let mut chunk_zs_vec = vec![0i32; count];

    env.get_int_array_region(min_xs, 0, &mut min_xs_vec).unwrap();
    env.get_int_array_region(min_ys, 0, &mut min_ys_vec).unwrap();
    env.get_int_array_region(min_zs, 0, &mut min_zs_vec).unwrap();
    env.get_int_array_region(max_ys, 0, &mut max_ys_vec).unwrap();
    env.get_int_array_region(chunk_xs, 0, &mut chunk_xs_vec).unwrap();
    env.get_int_array_region(chunk_zs, 0, &mut chunk_zs_vec).unwrap();

    let state = FRAME_STATE.read();

    let results: Vec<jint> = if count < 8 {
        (0..count)
            .map(|i| {
                compute_should_cull(
                    &state,
                    min_xs_vec[i] as f64,
                    min_ys_vec[i] as f64,
                    min_zs_vec[i] as f64,
                    max_ys_vec[i] as f64,
                    chunk_xs_vec[i],
                    chunk_zs_vec[i],
                )
            })
            .collect()
    } else {
        let min_xs_ref = &min_xs_vec;
        let min_ys_ref = &min_ys_vec;
        let min_zs_ref = &min_zs_vec;
        let max_ys_ref = &max_ys_vec;
        let chunk_xs_ref = &chunk_xs_vec;
        let chunk_zs_ref = &chunk_zs_vec;

        (0..count)
            .into_par_iter()
            .map(|i| {
                compute_should_cull(
                    &state,
                    min_xs_ref[i] as f64,
                    min_ys_ref[i] as f64,
                    min_zs_ref[i] as f64,
                    max_ys_ref[i] as f64,
                    chunk_xs_ref[i],
                    chunk_zs_ref[i],
                )
            })
            .collect()
    };

    let result_array = env.new_int_array(count as jsize).unwrap();
    env.set_int_array_region(&result_array, 0, &results).unwrap();
    result_array
}
