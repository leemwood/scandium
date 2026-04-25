use jni::objects::{JClass};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use parking_lot::RwLock;
use std::collections::HashMap;
use once_cell::sync::Lazy;

struct CullingCache {
    heights: HashMap<u64, i32>,
    transparency: HashMap<u64, bool>,
}

impl CullingCache {
    fn new() -> Self {
        Self {
            heights: HashMap::with_capacity(1024),
            transparency: HashMap::with_capacity(4096),
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
