import React, { useMemo, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useRouter } from 'expo-router';
import { useAppState } from '../../src/context/AppStateContext';
import { SPORT_META } from '../../src/data/sources';
import { getDaysSinceSync } from '../../src/engine/syncPipeline';
import { COLORS, FONTS, RADIUS, SPACING } from '../../src/theme';
import { SportId, UpcomingFixture } from '../../src/types';

const SPORT_FILTERS: Array<{ id: SportId | 'all'; label: string }> = [
  { id: 'all', label: 'ALL' },
  { id: 'football', label: 'FOOTBALL' },
  { id: 'nfl', label: 'NFL' },
  { id: 'nba', label: 'NBA' },
  { id: 'nhl', label: 'NHL' },
  { id: 'mlb', label: 'MLB' },
  { id: 'tennis', label: 'ATP' },
];

export default function SlateScreen() {
  const router = useRouter();
  const { modelState, settings, syncProgress, triggerSync } = useAppState();
  const [selectedSport, setSelectedSport] = useState<SportId | 'all'>('all');
  const [valueOnly, setValueOnly] = useState(false);

  const daysOld = getDaysSinceSync(modelState);
  const isStale = daysOld >= settings.syncIntervalDays;

  const filteredFixtures = useMemo(() => {
    return modelState.fixtures.filter((fx) => {
      if (selectedSport !== 'all' && fx.sport !== selectedSport) return false;
      if (valueOnly && !fx.bestSignal?.isValue) return false;
      return true;
    });
  }, [modelState.fixtures, selectedSport, valueOnly]);

  const valueCount = useMemo(
    () => modelState.fixtures.filter((f) => f.bestSignal?.isValue).length,
    [modelState.fixtures]
  );

  const renderFixture = ({ item }: { item: UpcomingFixture }) => {
    const sportMeta = SPORT_META[item.sport];
    const hasDraw = sportMeta.hasDraw;
    const kickoff = item.kickoffIso.slice(0, 16).replace('T', ' ');
    const sig = item.bestSignal;

    return (
      <Pressable
        style={styles.card}
        onPress={() => router.push(`/match/${encodeURIComponent(item.id)}`)}
      >
        <View style={styles.cardTop}>
          <View style={styles.leaguePill}>
            <View style={[styles.sportDot, { backgroundColor: sportMeta.color }]} />
            <Text style={styles.leagueText}>
              {sportMeta.short} · {item.leagueName.toUpperCase()}
            </Text>
          </View>
          <Text style={styles.kickoffText}>{kickoff} UTC</Text>
        </View>

        <Text style={styles.matchupTitle}>
          {item.homeTeam} <Text style={styles.vsText}>vs</Text> {item.awayTeam}
        </Text>

        <View style={styles.probRow}>
          <View style={styles.probCell}>
            <Text style={styles.probLabel}>HOME</Text>
            <Text style={styles.probValue}>{(item.prediction.probHome * 100).toFixed(1)}%</Text>
            {item.marketOdds && (
              <Text style={styles.oddsText}>@{item.marketOdds.home.toFixed(2)}</Text>
            )}
          </View>
          {hasDraw && (
            <View style={styles.probCell}>
              <Text style={styles.probLabel}>DRAW</Text>
              <Text style={styles.probValue}>{(item.prediction.probDraw * 100).toFixed(1)}%</Text>
              {item.marketOdds?.draw && (
                <Text style={styles.oddsText}>@{item.marketOdds.draw.toFixed(2)}</Text>
              )}
            </View>
          )}
          <View style={styles.probCell}>
            <Text style={styles.probLabel}>AWAY</Text>
            <Text style={styles.probValue}>{(item.prediction.probAway * 100).toFixed(1)}%</Text>
            {item.marketOdds && (
              <Text style={styles.oddsText}>@{item.marketOdds.away.toFixed(2)}</Text>
            )}
          </View>
        </View>

        {sig && sig.isValue ? (
          <View style={styles.valueBanner}>
            <Text style={styles.valueTag}>
              +{(sig.expectedValue * 100).toFixed(1)}% EV · {sig.label}
            </Text>
            <Text style={styles.kellyText}>
              Q-KELLY {(sig.kellyFraction * 100).toFixed(2)}% ($
              {(settings.bankroll * sig.kellyFraction).toFixed(0)})
            </Text>
          </View>
        ) : (
          <View style={styles.neutralFooter}>
            <Text style={styles.neutralText}>
              {item.prediction.expectedHomeGoals !== undefined
                ? `xG ${item.prediction.expectedHomeGoals.toFixed(2)} – ${item.prediction.expectedAwayGoals?.toFixed(2)} · O2.5 ${((item.prediction.probOver25 ?? 0) * 100).toFixed(0)}%`
                : `Model: ${item.prediction.modelSource.toUpperCase()} · Conf ${item.prediction.confidenceScore}%`}
            </Text>
            <Text style={styles.inspectText}>INSPECT →</Text>
          </View>
        )}
      </Pressable>
    );
  };

  return (
    <View style={styles.container}>
      {/* Offline Weekly Sync Status Bar */}
      <View style={styles.syncBar}>
        <View style={{ flex: 1 }}>
          <Text style={styles.syncTitle}>
            {modelState.isSeedData
              ? 'OFFLINE SEED PACK ACTIVE'
              : isStale
              ? `WEEKLY SYNC DUE (${daysOld}d OLD)`
              : `OFFLINE READY · SYNCED ${daysOld === 0 ? 'TODAY' : `${daysOld}d AGO`}`}
          </Text>
          <Text style={styles.syncSub}>
            {syncProgress.active
              ? `${syncProgress.completed}/${syncProgress.total} · ${syncProgress.label}`
              : `${modelState.totalHistoricalMatches} matches trained locally · ${valueCount} +EV edges`}
          </Text>
        </View>
        <Pressable
          style={[styles.syncBtn, syncProgress.active && styles.syncBtnDisabled]}
          onPress={triggerSync}
          disabled={syncProgress.active}
        >
          {syncProgress.active ? (
            <ActivityIndicator size="small" color={COLORS.text} />
          ) : (
            <Text style={styles.syncBtnText}>SYNC NOW</Text>
          )}
        </Pressable>
      </View>

      {/* Sport Filter Chips */}
      <View style={styles.filterWrap}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipRow}>
          {SPORT_FILTERS.map((f) => {
            const active = selectedSport === f.id;
            return (
              <Pressable
                key={f.id}
                style={[styles.chip, active && styles.chipActive]}
                onPress={() => setSelectedSport(f.id)}
              >
                <Text style={[styles.chipText, active && styles.chipTextActive]}>
                  {f.label}
                </Text>
              </Pressable>
            );
          })}
          <Pressable
            style={[styles.chip, valueOnly && styles.chipValueActive]}
            onPress={() => setValueOnly((v) => !v)}
          >
            <Text style={[styles.chipText, valueOnly && styles.chipValueText]}>
              +EV ONLY ({valueCount})
            </Text>
          </Pressable>
        </ScrollView>
      </View>

      <FlatList
        data={filteredFixtures}
        keyExtractor={(item) => item.id}
        renderItem={renderFixture}
        contentContainerStyle={styles.listContent}
        ListEmptyComponent={
          <View style={styles.emptyBox}>
            <Text style={styles.emptyTitle}>No fixtures match this filter</Text>
            <Text style={styles.emptySub}>
              Switch back to ALL sports, disable +EV ONLY, or use the Offline Simulator tab to score any custom matchup.
            </Text>
          </View>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.bg },
  syncBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: SPACING.md,
    paddingVertical: 10,
    backgroundColor: COLORS.surface,
    borderBottomWidth: 1,
    borderBottomColor: COLORS.border,
    gap: 12,
  },
  syncTitle: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    fontWeight: '700',
    color: COLORS.emerald,
    letterSpacing: 0.4,
  },
  syncSub: {
    fontSize: 12,
    color: COLORS.textMuted,
    marginTop: 2,
  },
  syncBtn: {
    backgroundColor: COLORS.accent,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: RADIUS.sm,
    minWidth: 88,
    alignItems: 'center',
  },
  syncBtnDisabled: { opacity: 0.6 },
  syncBtnText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    fontWeight: '700',
    color: COLORS.text,
  },
  filterWrap: {
    borderBottomWidth: 1,
    borderBottomColor: COLORS.border,
    backgroundColor: COLORS.bg,
  },
  chipRow: {
    paddingHorizontal: SPACING.md,
    paddingVertical: 10,
    gap: 8,
  },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: RADIUS.pill,
    borderWidth: 1,
    borderColor: COLORS.border,
    backgroundColor: COLORS.surface,
  },
  chipActive: {
    borderColor: COLORS.accent,
    backgroundColor: COLORS.accentDim,
  },
  chipValueActive: {
    borderColor: COLORS.emerald,
    backgroundColor: COLORS.emeraldDim,
  },
  chipText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    fontWeight: '600',
    color: COLORS.textSecondary,
  },
  chipTextActive: { color: COLORS.accent },
  chipValueText: { color: COLORS.emerald },
  listContent: { padding: SPACING.md, gap: 12, paddingBottom: 32 },
  card: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: SPACING.md,
  },
  cardTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  leaguePill: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  sportDot: { width: 7, height: 7, borderRadius: 4 },
  leagueText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    color: COLORS.textMuted,
    fontWeight: '600',
  },
  kickoffText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    color: COLORS.textMuted,
  },
  matchupTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 12,
  },
  vsText: { color: COLORS.textMuted, fontWeight: '400' },
  probRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 10,
  },
  probCell: {
    flex: 1,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    paddingVertical: 8,
    paddingHorizontal: 10,
    alignItems: 'center',
  },
  probLabel: {
    fontFamily: FONTS.mono,
    fontSize: 10,
    color: COLORS.textMuted,
  },
  probValue: {
    fontFamily: FONTS.mono,
    fontSize: 16,
    fontWeight: '700',
    color: COLORS.text,
    marginTop: 2,
  },
  oddsText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    color: COLORS.textSecondary,
    marginTop: 2,
  },
  valueBanner: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.emeraldDim,
    borderWidth: 1,
    borderColor: COLORS.emerald,
    borderRadius: RADIUS.sm,
    paddingHorizontal: 10,
    paddingVertical: 7,
  },
  valueTag: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    fontWeight: '700',
    color: COLORS.emerald,
  },
  kellyText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    color: COLORS.text,
  },
  neutralFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingTop: 6,
    borderTopWidth: 1,
    borderTopColor: COLORS.border,
  },
  neutralText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    color: COLORS.textMuted,
  },
  inspectText: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    fontWeight: '700',
    color: COLORS.accent,
  },
  emptyBox: {
    padding: SPACING.xl,
    alignItems: 'center',
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 6,
  },
  emptySub: {
    fontSize: 13,
    color: COLORS.textMuted,
    textAlign: 'center',
    lineHeight: 19,
  },
});
