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
import { profileKey } from '../../src/engine/teamStats';
import { TeamAvatar } from '../../src/components/TeamAvatar';
import { ProbBar } from '../../src/components/ProbBar';
import { FormDots } from '../../src/components/FormDots';
import { COLORS, RADIUS, SPACING } from '../../src/theme';
import { SportId, UpcomingFixture } from '../../src/types';

const SPORT_FILTERS: Array<{ id: SportId | 'all'; label: string }> = [
  { id: 'all', label: 'All Sports' },
  { id: 'football', label: 'Football' },
  { id: 'nfl', label: 'NFL' },
  { id: 'nba', label: 'NBA' },
  { id: 'nhl', label: 'NHL' },
  { id: 'mlb', label: 'MLB' },
  { id: 'tennis', label: 'Tennis' },
];

export default function GamesScreen() {
  const router = useRouter();
  const { modelState, settings, syncProgress, triggerSync } = useAppState();
  const [selectedSport, setSelectedSport] = useState<SportId | 'all'>('all');
  const [strongOnly, setStrongOnly] = useState(false);

  const daysOld = getDaysSinceSync(modelState);
  const isStale = daysOld >= settings.syncIntervalDays;

  const filteredFixtures = useMemo(() => {
    return modelState.fixtures.filter((fx) => {
      if (selectedSport !== 'all' && fx.sport !== selectedSport) return false;
      if (strongOnly) {
        const hasStrong = fx.markets?.topPicks?.some((p) => p.confidence === 'Strong') || fx.bestSignal?.isValue;
        if (!hasStrong) return false;
      }
      return true;
    });
  }, [modelState.fixtures, selectedSport, strongOnly]);

  const strongCount = useMemo(
    () =>
      modelState.fixtures.filter(
        (f) => f.markets?.topPicks?.some((p) => p.confidence === 'Strong') || f.bestSignal?.isValue
      ).length,
    [modelState.fixtures]
  );

  const renderFixture = ({ item }: { item: UpcomingFixture }) => {
    const sportMeta = SPORT_META[item.sport];
    const hasDraw = sportMeta.hasDraw;
    const kickoff = item.kickoffIso.slice(5, 16).replace('T', ' · ');
    const topPick = item.markets?.topPicks?.[0];
    const pH = modelState.teamProfiles?.[profileKey(item.sport, item.homeTeam)];
    const pA = modelState.teamProfiles?.[profileKey(item.sport, item.awayTeam)];

    return (
      <Pressable
        style={styles.card}
        onPress={() =>
          router.push({ pathname: '/match/[id]', params: { id: item.id } })
        }
      >
        {/* Top Row: League & Date */}
        <View style={styles.cardTop}>
          <View style={styles.leaguePill}>
            <View style={[styles.sportDot, { backgroundColor: sportMeta.color }]} />
            <Text style={styles.leagueText}>
              {item.leagueName}
              {item.surface ? ` · ${item.surface}` : ''}
            </Text>
          </View>
          <Text style={styles.kickoffText}>{kickoff} UTC</Text>
        </View>

        {/* Teams Row with Avatars, Power Scores & Recent Form */}
        <View style={styles.teamsRow}>
          <View style={styles.teamSide}>
            <TeamAvatar teamName={item.homeTeam} size={38} />
            <View style={{ flex: 1 }}>
              <Text numberOfLines={1} style={styles.teamName}>
                {item.homeTeam}
              </Text>
              <Text style={styles.powerMeta}>Power {pH?.powerScore ?? 55}/99</Text>
            </View>
          </View>

          <Text style={styles.vsBadge}>VS</Text>

          <View style={[styles.teamSide, { justifyContent: 'flex-end' }]}>
            <View style={{ flex: 1, alignItems: 'flex-end' }}>
              <Text numberOfLines={1} style={styles.teamName}>
                {item.awayTeam}
              </Text>
              <Text style={styles.powerMeta}>Power {pA?.powerScore ?? 55}/99</Text>
            </View>
            <TeamAvatar teamName={item.awayTeam} size={38} />
          </View>
        </View>

        {/* Visual Stacked Win Chance Bar */}
        <ProbBar
          homeLabel={item.homeTeam.split(' ')[0]}
          drawLabel="Draw"
          awayLabel={item.awayTeam.split(' ')[0]}
          homeProb={item.prediction.probHome}
          drawProb={hasDraw ? item.prediction.probDraw : undefined}
          awayProb={item.prediction.probAway}
        />

        {/* Form Dots Strip */}
        {(pH?.recentForm?.length || pA?.recentForm?.length) ? (
          <View style={styles.formStrip}>
            <FormDots results={pH?.recentForm ?? []} size="sm" highlightLatest={false} />
            <Text style={styles.formCenterLabel}>Last 5</Text>
            <FormDots results={pA?.recentForm ?? []} size="sm" highlightLatest={false} />
          </View>
        ) : null}

        {/* Plain-English Best Pick Callout */}
        {topPick ? (
          <View style={styles.pickBox}>
            <View style={styles.pickHeader}>
              <View
                style={[
                  styles.confPill,
                  topPick.confidence === 'Strong'
                    ? styles.confStrong
                    : topPick.confidence === 'Good'
                    ? styles.confGood
                    : styles.confLean,
                ]}
              >
                <Text
                  style={[
                    styles.confText,
                    topPick.confidence === 'Strong'
                      ? { color: COLORS.emerald }
                      : topPick.confidence === 'Good'
                      ? { color: COLORS.accent }
                      : { color: COLORS.amber },
                  ]}
                >
                  {topPick.confidence} Pick · {topPick.chancePct}%
                </Text>
              </View>
              <Text style={styles.allMarketsLink}>All Markets →</Text>
            </View>
            <Text style={styles.pickTitle}>{topPick.label}</Text>
            <Text numberOfLines={2} style={styles.pickReason}>
              {topPick.plainReason}
            </Text>
          </View>
        ) : null}
      </Pressable>
    );
  };

  return (
    <View style={styles.container}>
      {/* Offline Weekly Update Header */}
      <View style={styles.syncBar}>
        <View style={{ flex: 1 }}>
          <Text style={styles.syncTitle}>
            {modelState.isSeedData
              ? 'Ready Offline · Starter Data Loaded'
              : isStale
              ? `Weekly Update Ready (${daysOld} days old)`
              : `Ready Offline · Updated ${daysOld === 0 ? 'Today' : `${daysOld}d ago`}`}
          </Text>
          <Text style={styles.syncSub}>
            {syncProgress.active
              ? `Updating (${syncProgress.completed}/${syncProgress.total}): ${syncProgress.label}`
              : `${modelState.totalHistoricalMatches} past games analyzed on your phone`}
          </Text>
        </View>
        <Pressable
          style={[styles.syncBtn, syncProgress.active && { opacity: 0.6 }]}
          onPress={triggerSync}
          disabled={syncProgress.active}
        >
          {syncProgress.active ? (
            <ActivityIndicator size="small" color={COLORS.text} />
          ) : (
            <Text style={styles.syncBtnText}>Update</Text>
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
                <Text style={[styles.chipText, active && styles.chipTextActive]}>{f.label}</Text>
              </Pressable>
            );
          })}
          <Pressable
            style={[styles.chip, strongOnly && styles.chipStrongActive]}
            onPress={() => setStrongOnly((v) => !v)}
          >
            <Text style={[styles.chipText, strongOnly && { color: COLORS.emerald }]}>
              Strong Picks ({strongCount})
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
            <Text style={styles.emptyTitle}>No games match this filter</Text>
            <Text style={styles.emptySub}>
              Switch back to All Sports, turn off Strong Picks, or open the Compare tab to predict any matchup offline.
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
    paddingVertical: 11,
    backgroundColor: COLORS.surface,
    borderBottomWidth: 1,
    borderBottomColor: COLORS.border,
    gap: 12,
  },
  syncTitle: { fontSize: 13, fontWeight: '700', color: COLORS.emerald },
  syncSub: { fontSize: 12, color: COLORS.textMuted, marginTop: 2 },
  syncBtn: {
    backgroundColor: COLORS.accent,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: RADIUS.sm,
    minWidth: 76,
    alignItems: 'center',
  },
  syncBtnText: { fontSize: 12, fontWeight: '700', color: COLORS.text },
  filterWrap: { borderBottomWidth: 1, borderBottomColor: COLORS.border },
  chipRow: { paddingHorizontal: SPACING.md, paddingVertical: 10, gap: 8 },
  chip: {
    paddingHorizontal: 13,
    paddingVertical: 7,
    borderRadius: RADIUS.pill,
    borderWidth: 1,
    borderColor: COLORS.border,
    backgroundColor: COLORS.surface,
  },
  chipActive: { borderColor: COLORS.accent, backgroundColor: COLORS.accentDim },
  chipStrongActive: { borderColor: COLORS.emerald, backgroundColor: COLORS.emeraldDim },
  chipText: { fontSize: 12, fontWeight: '700', color: COLORS.textSecondary },
  chipTextActive: { color: COLORS.accent },
  listContent: { padding: SPACING.md, gap: 14, paddingBottom: 32 },
  card: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 15,
    gap: 12,
  },
  cardTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  leaguePill: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  sportDot: { width: 8, height: 8, borderRadius: 4 },
  leagueText: { fontSize: 12, fontWeight: '700', color: COLORS.textSecondary },
  kickoffText: { fontSize: 11, color: COLORS.textMuted, fontWeight: '600' },
  teamsRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  teamSide: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 10 },
  teamName: { fontSize: 15, fontWeight: '800', color: COLORS.text },
  powerMeta: { fontSize: 11, color: COLORS.textMuted, marginTop: 2, fontWeight: '600' },
  vsBadge: {
    fontSize: 11,
    fontWeight: '800',
    color: COLORS.textMuted,
    paddingHorizontal: 6,
  },
  formStrip: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 4,
  },
  formCenterLabel: { fontSize: 11, fontWeight: '600', color: COLORS.textMuted },
  pickBox: {
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.md,
    padding: 12,
    borderWidth: 1,
    borderColor: COLORS.border,
    gap: 5,
  },
  pickHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  confPill: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: RADIUS.pill },
  confStrong: { backgroundColor: COLORS.emeraldDim },
  confGood: { backgroundColor: COLORS.accentDim },
  confLean: { backgroundColor: COLORS.amberDim },
  confText: { fontSize: 11, fontWeight: '800' },
  allMarketsLink: { fontSize: 12, fontWeight: '700', color: COLORS.accent },
  pickTitle: { fontSize: 15, fontWeight: '800', color: COLORS.text, marginTop: 2 },
  pickReason: { fontSize: 12.5, color: COLORS.textSecondary, lineHeight: 18 },
  emptyBox: { padding: SPACING.xl, alignItems: 'center' },
  emptyTitle: { fontSize: 16, fontWeight: '700', color: COLORS.text, marginBottom: 6 },
  emptySub: { fontSize: 13, color: COLORS.textMuted, textAlign: 'center', lineHeight: 19 },
});
