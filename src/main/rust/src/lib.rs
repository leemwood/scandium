use jni::objects::{JClass};
use jni::sys::{jboolean, jint, jlong, jdouble};
use jni::JNIEnv;
use parking_lot::RwLock;
use std::collections::HashMap;
use once_cell::sync::Lazy;

struct FrameState {
    cam_x: f64, cam_y: f64, cam_z: f64,
    look_x: f64, look_y: f64, look_z: f64,
    fov_cos_threshold: f64,
    is_underground: bool,
    is_nether: bool,
    has_ceiling: bool,
    reserved_height: i32,
    fov_enabled: bool,
    mountain_enabled: bool,
    vertical_enabled: bool,
    aggressive_vertical: bool,
}

impl Default for FrameState {
    fn default() -> Self {
        Self {
            cam_x: 0.0, cam_y: 0.0, cam_z: 0.0,
            look_x: 0.0, look_y: 0.0, look_z: 1.0,
            fov_cos_threshold: 0.0,
            is_underground: false,
            is_nether: false,
            has_ceiling: false,
            reserved_height: 16,
            fov_enabled: true,
            mountain_enabled: true,
            vertical_enabled: true,
            aggressive_vertical: false,
        }
    }
}

struct CullingCache {
    heights: HashMap<u64, i32>,
    transparency: HashMap<u64, bool>,
    state: FrameState,
}

impl CullingCache {
    fn new() -> Self {
        Self {
            heights: HashMap::with_capacity(1024),
            transparency: HashMap::with_capacity(4096),
            state: FrameState::default(),
        }
    }

    fn clear(&mut self) {
        self.heights.clear();
        self.transparency.clear();
    }
}

static CACHE: Lazy<RwLock<CullingCache>> = Lazy::new(|| RwLock::new(CullingCache::new()));

const MAX_TRANSPARENCY_CACHE_SIZE: usize = 4096;
const MAX_HEIGHT_CACHE_SIZE: usize = 1024;

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeResetCache(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut cache = CACHE.write();
    cache.clear();
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeGetCachedHeight(
    _env: JNIEnv,
    _class: JClass,
    key: jlong,
) -> jint {
    let cache = CACHE.read();
    *cache.heights.get(&(key as u64)).unwrap_or(&-1)
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativePutCachedHeight(
    _env: JNIEnv,
    _class: JClass,
    key: jlong,
    value: jint,
) {
    let mut cache = CACHE.write();
    cache.heights.insert(key as u64, value);
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeGetCachedTransparency(
    _env: JNIEnv,
    _class: JClass,
    key: jlong,
) -> jint {
    let cache = CACHE.read();
    match cache.transparency.get(&(key as u64)) {
        Some(&true) => 1,
        Some(&false) => 0,
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
    let mut cache = CACHE.write();
    cache.transparency.insert(key as u64, value != 0);
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeCleanupCaches(
    _env: JNIEnv,
    _class: JClass,
    player_chunk_x: jint,
    player_chunk_z: jint,
) {
    let mut cache = CACHE.write();
    
    if cache.transparency.len() > MAX_TRANSPARENCY_CACHE_SIZE {
        cache.transparency.clear();
    }

    if cache.heights.len() > MAX_HEIGHT_CACHE_SIZE {
        cache.heights.retain(|&key, _| {
            let chunk_x = (key >> 32) as i32;
            let chunk_z = (key & 0xFFFFFFFF) as i32;
            let dist = (chunk_x - player_chunk_x).abs() + (chunk_z - player_chunk_z).abs();
            dist <= 32
        });
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeUpdateFrameState(
    _env: JNIEnv,
    _class: JClass,
    camX: jdouble, camY: jdouble, camZ: jdouble,
    lookX: jdouble, lookY: jdouble, lookZ: jdouble,
    fovCos: jdouble,
    isUnderground: jboolean,
    isNether: jboolean,
    hasCeiling: jboolean,
    reservedHeight: jint,
    fovEnabled: jboolean,
    mountainEnabled: jboolean,
    verticalEnabled: jboolean,
    aggressiveVertical: jboolean,
) {
    let mut cache = CACHE.write();
    cache.state.cam_x = camX;
    cache.state.cam_y = camY;
    cache.state.cam_z = camZ;
    cache.state.look_x = lookX;
    cache.state.look_y = lookY;
    cache.state.look_z = lookZ;
    cache.state.fov_cos_threshold = fovCos;
    cache.state.is_underground = isUnderground != 0;
    cache.state.is_nether = isNether != 0;
    cache.state.has_ceiling = hasCeiling != 0;
    cache.state.reserved_height = reservedHeight;
    cache.state.fov_enabled = fovEnabled != 0;
    cache.state.mountain_enabled = mountainEnabled != 0;
    cache.state.vertical_enabled = verticalEnabled != 0;
    cache.state.aggressive_vertical = aggressiveVertical != 0;
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_cn_lemwood_client_util_CullingUtils_nativeShouldCull(
    _env: JNIEnv,
    _class: JClass,
    minX: jdouble, minY: jdouble, minZ: jdouble,
    maxX: jdouble, maxY: jdouble, maxZ: jdouble,
    chunkX: jint, chunkZ: jint,
) -> jint {
    let cache = CACHE.read();
    let s = &cache.state;

    // 1. FOV Culling
    if s.fov_enabled {
        let center_x = minX + 8.0;
        let center_y = minY + 8.0;
        let center_z = minZ + 8.0;

        let dx = center_x - s.cam_x;
        let dy = center_y - s.cam_y;
        let dz = center_z - s.cam_z;
        let dist_sq = dx * dx + dy * dy + dz * dz;

        let min_fov_dist_sq = if s.is_underground { 256.0 } else { 400.0 };

        if dist_sq > min_fov_dist_sq {
            let inv_dist = 1.0 / dist_sq.sqrt();
            let dot = (s.look_x * dx + s.look_y * dy + s.look_z * dz) * inv_dist;
            if dot < s.fov_cos_threshold {
                return 1; // FOV
            }
        }
    }

    // 2. Heightmap-based Culling
    let key = ((chunkX as u64) << 32) | ((chunkZ as u32) as u64);
    let surface_y = *cache.heights.get(&key).unwrap_or(&-1);

    if surface_y != -1 {
        let surface_y_f = surface_y as f64;
        
        // Mountain Culling
        if s.mountain_enabled {
            if s.is_nether || (s.has_ceiling && s.cam_y < 120.0) {
                if s.cam_y < 110.0 && minY > 128.0 {
                    return 2; // Mountain
                }
            } else if s.is_underground {
                if minY > surface_y_f + 8.0 {
                    let dx = (minX + 8.0) - s.cam_x;
                    let dz = (minZ + 8.0) - s.cam_z;
                    if dx * dx + dz * dz > 1024.0 {
                        let dy = (minY + 8.0) - s.cam_y;
                        let dist = (dx * dx + dy * dy + dz * dz).sqrt();
                        if dist > 0.1 {
                            let dot = (s.look_x * dx + s.look_y * dy + s.look_z * dz) / dist;
                            if dot < s.fov_cos_threshold {
                                return 2; // Mountain
                            }
                        }
                    }
                }
            }
        }

        // Vertical Culling
        if s.vertical_enabled {
            if s.is_nether {
                let diff_y = (minY + 8.0 - s.cam_y).abs();
                if diff_y > (s.reserved_height + 32) as f64 {
                    return 3; // Vertical
                }
            } else if !s.is_underground {
                let vertical_offset = if s.aggressive_vertical { 32.0 } else { 64.0 };
                if maxY < surface_y_f - vertical_offset && maxY < s.cam_y - vertical_offset {
                    return 3; // Vertical
                }
            } else {
                let underground_offset = if s.aggressive_vertical { s.reserved_height / 2 } else { s.reserved_height };
                let diff_y = (minY + 8.0 - s.cam_y).abs();
                if diff_y > underground_offset as f64 {
                    let dx = (minX + 8.0) - s.cam_x;
                    let dz = (minZ + 8.0) - s.cam_z;
                    let dy = (minY + 8.0) - s.cam_y;
                    let dist = (dx * dx + dy * dy + dz * dz).sqrt();
                    if dist > 0.1 {
                        let dot = (s.look_x * dx + s.look_y * dy + s.look_z * dz) / dist;
                        if dot < s.fov_cos_threshold {
                            return 3; // Vertical
                        }
                    }
                }
                
                let surface_offset = if s.aggressive_vertical { -8.0 } else { 0.0 };
                if s.cam_y < surface_y_f - 16.0 && minY > surface_y_f + surface_offset {
                    return 3; // Vertical
                }
            }
        }
    }

    0
}
