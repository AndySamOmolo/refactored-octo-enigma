import AsyncStorage from '@react-native-async-storage/async-storage';
import { PaperBet, SyncedModelState, UserSettings } from '../types';
import { buildInitialSeedState, DEFAULT_USER_SETTINGS } from '../engine/syncPipeline';

const KEYS = {
  MODEL_STATE: '@edgeledger/model_state_v3',
  SETTINGS: '@edgeledger/settings_v2',
  PAPER_BETS: '@edgeledger/paper_bets_v1',
};

export async function loadSettings(): Promise<UserSettings> {
  try {
    const raw = await AsyncStorage.getItem(KEYS.SETTINGS);
    if (!raw) return DEFAULT_USER_SETTINGS;
    return { ...DEFAULT_USER_SETTINGS, ...JSON.parse(raw) };
  } catch {
    return DEFAULT_USER_SETTINGS;
  }
}

export async function saveSettings(settings: UserSettings): Promise<void> {
  await AsyncStorage.setItem(KEYS.SETTINGS, JSON.stringify(settings));
}

export async function loadModelState(settings: UserSettings): Promise<SyncedModelState> {
  try {
    const raw = await AsyncStorage.getItem(KEYS.MODEL_STATE);
    if (!raw) {
      const seed = buildInitialSeedState(settings);
      await AsyncStorage.setItem(KEYS.MODEL_STATE, JSON.stringify(seed));
      return seed;
    }
    const parsed = JSON.parse(raw) as SyncedModelState;
    if (!parsed || parsed.schemaVersion !== 3 || !Array.isArray(parsed.fixtures) || !parsed.teamProfiles) {
      const seed = buildInitialSeedState(settings);
      await AsyncStorage.setItem(KEYS.MODEL_STATE, JSON.stringify(seed));
      return seed;
    }
    return parsed;
  } catch {
    return buildInitialSeedState(settings);
  }
}

export async function saveModelState(state: SyncedModelState): Promise<void> {
  await AsyncStorage.setItem(KEYS.MODEL_STATE, JSON.stringify(state));
}

export async function loadPaperBets(): Promise<PaperBet[]> {
  try {
    const raw = await AsyncStorage.getItem(KEYS.PAPER_BETS);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export async function savePaperBets(bets: PaperBet[]): Promise<void> {
  await AsyncStorage.setItem(KEYS.PAPER_BETS, JSON.stringify(bets));
}
