import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { AppStateProvider } from '../src/context/AppStateContext';
import { COLORS, RADIUS } from '../src/theme';

interface BoundaryState {
  hasError: boolean;
}

class AppErrorBoundary extends React.Component<
  { children: React.ReactNode },
  BoundaryState
> {
  state: BoundaryState = { hasError: false };

  static getDerivedStateFromError(): BoundaryState {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return (
        <View style={styles.fallback}>
          <Text style={styles.fallbackTitle}>Something went wrong</Text>
          <Text style={styles.fallbackSub}>
            Tap below to reload the screen safely without losing your saved picks.
          </Text>
          <Pressable
            style={styles.retryBtn}
            onPress={() => this.setState({ hasError: false })}
          >
            <Text style={styles.retryText}>Reload Screen</Text>
          </Pressable>
        </View>
      );
    }
    return this.props.children;
  }
}

export default function RootLayout() {
  return (
    <AppErrorBoundary>
      <AppStateProvider>
        <StatusBar style="light" backgroundColor={COLORS.bg} />
        <Stack
          screenOptions={{
            headerStyle: { backgroundColor: COLORS.bg },
            headerTintColor: COLORS.text,
            contentStyle: { backgroundColor: COLORS.bg },
            headerShadowVisible: false,
          }}
        >
          <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
          <Stack.Screen
            name="match/[id]"
            options={{
              title: 'Match Breakdown',
              headerBackTitle: 'Games',
            }}
          />
        </Stack>
      </AppStateProvider>
    </AppErrorBoundary>
  );
}

const styles = StyleSheet.create({
  fallback: {
    flex: 1,
    backgroundColor: COLORS.bg,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    gap: 12,
  },
  fallbackTitle: { fontSize: 18, fontWeight: '800', color: COLORS.text },
  fallbackSub: { fontSize: 13, color: COLORS.textMuted, textAlign: 'center', lineHeight: 19 },
  retryBtn: {
    backgroundColor: COLORS.accent,
    paddingHorizontal: 18,
    paddingVertical: 10,
    borderRadius: RADIUS.sm,
    marginTop: 6,
  },
  retryText: { fontSize: 13, fontWeight: '700', color: COLORS.text },
});
