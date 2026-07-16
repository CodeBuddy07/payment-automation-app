import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  ViewStyle,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { palette, radius, spacing, typography } from '../theme/theme';

export const Screen: React.FC<{
  children: React.ReactNode;
  scroll?: boolean;
  refreshing?: boolean;
  onRefresh?: () => void;
  padded?: boolean;
}> = ({ children, scroll = true, padded = true }) => {
  const content = (
    <View style={[padded && styles.padded, { flexGrow: 1 }]}>{children}</View>
  );
  return (
    <SafeAreaView style={styles.screen} edges={['top', 'left', 'right']}>
      {scroll ? (
        <ScrollView
          contentContainerStyle={{ paddingBottom: spacing.xxl }}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}>
          {content}
        </ScrollView>
      ) : (
        content
      )}
    </SafeAreaView>
  );
};

export const ScreenHeader: React.FC<{ title: string; subtitle?: string; right?: React.ReactNode }> = ({
  title,
  subtitle,
  right,
}) => (
  <View style={styles.header}>
    <View style={{ flex: 1 }}>
      <Text style={styles.headerTitle}>{title}</Text>
      {!!subtitle && <Text style={styles.headerSubtitle}>{subtitle}</Text>}
    </View>
    {right}
  </View>
);

export const Card: React.FC<{ children: React.ReactNode; style?: ViewStyle; onPress?: () => void }> = ({
  children,
  style,
  onPress,
}) => {
  const inner = <View style={[styles.card, style]}>{children}</View>;
  if (onPress) {
    return (
      <Pressable onPress={onPress} style={({ pressed }) => pressed && styles.pressed}>
        {inner}
      </Pressable>
    );
  }
  return inner;
};

export const SectionLabel: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <Text style={styles.sectionLabel}>{String(children).toUpperCase()}</Text>
);

export const Badge: React.FC<{ label: string; color?: string }> = ({ label, color = palette.accent }) => (
  <View style={[styles.badge, { borderColor: color }]}>
    <View style={[styles.dot, { backgroundColor: color }]} />
    <Text style={[styles.badgeText, { color }]}>{label}</Text>
  </View>
);

export const StatTile: React.FC<{ label: string; value: string | number; color?: string }> = ({
  label,
  value,
  color = palette.text,
}) => (
  <View style={styles.statTile}>
    <Text style={[styles.statValue, { color }]}>{value}</Text>
    <Text style={styles.statLabel}>{label.toUpperCase()}</Text>
  </View>
);

export const Button: React.FC<{
  title: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  loading?: boolean;
  disabled?: boolean;
  style?: ViewStyle;
}> = ({ title, onPress, variant = 'primary', loading, disabled, style }) => {
  const bg =
    variant === 'primary'
      ? palette.accent
      : variant === 'danger'
      ? palette.danger
      : variant === 'ghost'
      ? 'transparent'
      : palette.surfaceHigh;
  const fg = variant === 'primary' || variant === 'danger' ? palette.bg : palette.text;
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: bg, opacity: disabled ? 0.5 : pressed ? 0.85 : 1 },
        variant === 'ghost' && styles.buttonGhost,
        style,
      ]}>
      {loading ? (
        <ActivityIndicator color={fg} />
      ) : (
        <Text style={[styles.buttonText, { color: fg }]}>{title}</Text>
      )}
    </Pressable>
  );
};

export const Row: React.FC<{ children: React.ReactNode; style?: ViewStyle }> = ({ children, style }) => (
  <View style={[styles.row, style]}>{children}</View>
);

export const Divider: React.FC = () => <View style={styles.divider} />;

export const EmptyState: React.FC<{ title: string; subtitle?: string }> = ({ title, subtitle }) => (
  <View style={styles.empty}>
    <Text style={styles.emptyTitle}>{title}</Text>
    {!!subtitle && <Text style={styles.emptySubtitle}>{subtitle}</Text>}
  </View>
);

export const KeyValue: React.FC<{ label: string; value?: string | number | null; mono?: boolean }> = ({
  label,
  value,
  mono,
}) => (
  <View style={styles.kv}>
    <Text style={styles.kvLabel}>{label}</Text>
    <Text style={[styles.kvValue, mono && typography.mono]} numberOfLines={2}>
      {value === null || value === undefined || value === '' ? '—' : String(value)}
    </Text>
  </View>
);

export const Loading: React.FC = () => (
  <View style={styles.loading}>
    <ActivityIndicator color={palette.accent} size="large" />
  </View>
);

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: palette.bg },
  padded: { paddingHorizontal: spacing.lg },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingTop: spacing.md,
    paddingBottom: spacing.lg,
  },
  headerTitle: { ...typography.display, color: palette.text },
  headerSubtitle: { ...typography.body, color: palette.textMuted, marginTop: 2 },
  card: {
    backgroundColor: palette.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: palette.border,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  pressed: { opacity: 0.85 },
  sectionLabel: {
    ...typography.label,
    color: palette.textFaint,
    marginBottom: spacing.sm,
    marginTop: spacing.md,
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.sm,
    paddingVertical: 3,
  },
  dot: { width: 6, height: 6, borderRadius: 3, marginRight: 6 },
  badgeText: { ...typography.label, fontSize: 11 },
  statTile: {
    flex: 1,
    backgroundColor: palette.surfaceAlt,
    borderRadius: radius.md,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: palette.border,
  },
  statValue: { fontSize: 22, fontWeight: '700' },
  statLabel: { ...typography.label, color: palette.textFaint, marginTop: 4, fontSize: 10 },
  button: {
    borderRadius: radius.md,
    paddingVertical: 13,
    paddingHorizontal: spacing.lg,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonGhost: { borderWidth: 1, borderColor: palette.border },
  buttonText: { ...typography.heading },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  divider: { height: 1, backgroundColor: palette.border, marginVertical: spacing.md },
  empty: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.xxl * 1.5 },
  emptyTitle: { ...typography.heading, color: palette.textMuted },
  emptySubtitle: { ...typography.body, color: palette.textFaint, marginTop: 6, textAlign: 'center' },
  kv: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: spacing.sm,
    gap: spacing.lg,
  },
  kvLabel: { ...typography.body, color: palette.textMuted },
  kvValue: { ...typography.body, color: palette.text, flexShrink: 1, textAlign: 'right' },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: palette.bg },
});
