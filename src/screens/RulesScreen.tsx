import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useCallback } from 'react';
import { Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { Badge, Button, EmptyState, Screen, ScreenHeader } from '../components/ui';
import type { Rule } from '../native/types';
import type { RootStackParamList } from '../navigation/types';
import { useRulesStore } from '../store';
import { emptyDraft, fromRule, useRuleDraftStore } from '../store/ruleDraft';
import { palette, radius, spacing, typography } from '../theme/theme';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const RulesScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const { rules, load, toggle } = useRulesStore();
  const setDraft = useRuleDraftStore(s => s.setDraft);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const createNew = () => {
    setDraft(emptyDraft());
    nav.navigate('CreateRule', {});
  };

  const edit = (rule: Rule) => {
    setDraft(fromRule(rule));
    nav.navigate('CreateRule', { ruleId: rule.id });
  };

  return (
    <Screen>
      <ScreenHeader
        title="Rules"
        subtitle={`${rules.length} configured`}
        right={
          <Pressable onPress={createNew} style={styles.addBtn}>
            <Text style={styles.addText}>+ New</Text>
          </Pressable>
        }
      />

      {rules.length === 0 && (
        <EmptyState
          title="No rules yet"
          subtitle="Create a rule to match a sender, extract data, and forward it to a webhook."
        />
      )}

      {rules.map(rule => (
        <Pressable key={rule.id} style={styles.row} onPress={() => edit(rule)}>
          <View style={{ flex: 1 }}>
            <Text style={styles.name}>{rule.name || 'Untitled rule'}</Text>
            <Text style={styles.sub}>
              {rule.senderMatchType} · {rule.senderPattern || 'any sender'}
            </Text>
            <View style={styles.metaRow}>
              <Badge label={rule.processorType.toUpperCase()} color={palette.info} />
              <Text style={styles.matches}>{rule.matchCount} matches</Text>
            </View>
          </View>
          <View style={styles.controls}>
            <Switch
              value={rule.enabled}
              onValueChange={v => toggle(rule.id, v)}
              trackColor={{ false: palette.surfaceHigh, true: palette.accentDim }}
              thumbColor={rule.enabled ? palette.accent : palette.textFaint}
            />
            <Pressable onPress={() => nav.navigate('RuleTesting', { ruleId: rule.id })}>
              <Text style={styles.testLink}>Test</Text>
            </Pressable>
          </View>
        </Pressable>
      ))}

      {rules.length > 0 && (
        <Button title="Create rule" variant="secondary" onPress={createNew} style={{ marginTop: spacing.sm }} />
      )}
    </Screen>
  );
};

const styles = StyleSheet.create({
  addBtn: {
    backgroundColor: palette.accent,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: 7,
  },
  addText: { ...typography.label, color: palette.bg },
  row: {
    flexDirection: 'row',
    backgroundColor: palette.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    padding: spacing.md,
    marginBottom: spacing.sm,
    alignItems: 'center',
  },
  name: { ...typography.heading, color: palette.text },
  sub: { ...typography.body, color: palette.textMuted, marginTop: 2, fontSize: 12 },
  metaRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginTop: spacing.sm },
  matches: { ...typography.body, color: palette.textFaint, fontSize: 11 },
  controls: { alignItems: 'flex-end', gap: spacing.sm },
  testLink: { ...typography.label, color: palette.accent },
});

export default RulesScreen;
