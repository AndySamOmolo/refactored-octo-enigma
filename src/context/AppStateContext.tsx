import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { PaperBet, SyncedModelState, UserSettings } from '../types';
import {
  buildInitialSeedState,
  DEFAULT_USER_SETTINGS,
  runOnDeviceWeeklySync,
  shouldAutoSyncWeekly,
} from '../engine/syncPipeline';
import {
  loadModelState,
  loadPaperBets,
  loadSettings,
  saveModelState,
  savePaperBets,
  saveSettings,
} from '../storage/store';

interface SyncProgress {
  active: boolean;
  completed: number;
  total: number;
  label: string;
}

interface AppStateValue {
  hydrated: boolean;
  modelState: SyncedModelState;
  settings: UserSettings;
  paperBets: PaperBet[];
  syncProgress: SyncProgress;
  triggerSync: () => Promise<void>;
  updateSettings: (patch: Partial<UserSettings>) => Promise<void>;
  addPaperBet: (bet: Omit<PaperBet, 'id' | 'createdAt' | 'status'>) => Promise<void>;
  settlePaperBet: (id: string, status: PaperBet['status']) => Promise<void>;
  deletePaperBet: (id: string) => Promise<void>;
}

const AppStateContext = createContext<AppStateValue | null>(null);

export function AppStateProvider({ children }: { children: React.ReactNode }) {
  const [hydrated, setHydrated] = useState(false);
  const [settings, setSettings] = useState<UserSettings>(DEFAULT_USER_SETTINGS);
  const [modelState, setModelState] = useState<SyncedModelState>(() =>
    buildInitialSeedState(DEFAULT_USER_SETTINGS)
  );
  const [paperBets, setPaperBets] = useState<PaperBet[]>([]);
  const [syncProgress, setSyncProgress] = useState<SyncProgress>({
    active: false,
    completed: 0,
    total: 0,
    label: '',
  });

  const executeSync = useCallback(async (activeSettings: UserSettings) => {
    setSyncProgress({ active: true, completed: 0, total: 1, label: 'Connecting to public feeds...' });
    try {
      const next = await runOnDeviceWeeklySync(activeSettings, (completed, total, label) => {
        setSyncProgress({ active: true, completed, total, label });
      });
      setModelState(next);
      await saveModelState(next);
    } finally {
      setSyncProgress({ active: false, completed: 0, total: 0, label: '' });
    }
  }, []);

  useEffect(() => {
    let mounted = true;
    (async () => {
      const [loadedSettings, loadedBets] = await Promise.all([
        loadSettings(),
        loadPaperBets(),
      ]);
      const loadedModel = await loadModelState(loadedSettings);
      if (!mounted) return;
      setSettings(loadedSettings);
      setPaperBets(loadedBets);
      setModelState(loadedModel);
      setHydrated(true);

      // Automatically trigger weekly on-device sync if >= 7 days old (fails silently if offline)
      if (shouldAutoSyncWeekly(loadedModel, loadedSettings)) {
        executeSync(loadedSettings).catch(() => {});
      }
    })();
    return () => {
      mounted = false;
    };
  }, [executeSync]);

  const triggerSync = useCallback(async () => {
    if (syncProgress.active) return;
    await executeSync(settings);
  }, [executeSync, settings, syncProgress.active]);

  const updateSettings = useCallback(
    async (patch: Partial<UserSettings>) => {
      const next = { ...settings, ...patch };
      setSettings(next);
      await saveSettings(next);
    },
    [settings]
  );

  const addPaperBet = useCallback(
    async (bet: Omit<PaperBet, 'id' | 'createdAt' | 'status'>) => {
      const created: PaperBet = {
        ...bet,
        id: `pb-${Date.now()}-${Math.floor(Math.random() * 1000)}`,
        createdAt: new Date().toISOString(),
        status: 'PENDING',
      };
      const next = [created, ...paperBets];
      setPaperBets(next);
      await savePaperBets(next);
    },
    [paperBets]
  );

  const settlePaperBet = useCallback(
    async (id: string, status: PaperBet['status']) => {
      const next = paperBets.map((b) => (b.id === id ? { ...b, status } : b));
      setPaperBets(next);
      await savePaperBets(next);
    },
    [paperBets]
  );

  const deletePaperBet = useCallback(
    async (id: string) => {
      const next = paperBets.filter((b) => b.id !== id);
      setPaperBets(next);
      await savePaperBets(next);
    },
    [paperBets]
  );

  return (
    <AppStateContext.Provider
      value={{
        hydrated,
        modelState,
        settings,
        paperBets,
        syncProgress,
        triggerSync,
        updateSettings,
        addPaperBet,
        settlePaperBet,
        deletePaperBet,
      }}
    >
      {children}
    </AppStateContext.Provider>
  );
}

export function useAppState(): AppStateValue {
  const ctx = useContext(AppStateContext);
  if (!ctx) {
    throw new Error('useAppState must be used within AppStateProvider');
  }
  return ctx;
}
