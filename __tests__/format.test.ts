/**
 * Pure-logic unit tests (no native module required).
 * @format
 */
import { formatTime, prettyJson, relativeTime, truncate } from '../src/utils/format';

describe('format utils', () => {
  test('truncate respects max length', () => {
    expect(truncate('hello world', 5)).toBe('hello…');
    expect(truncate('short', 50)).toBe('short');
    expect(truncate('', 10)).toBe('');
  });

  test('formatTime handles null', () => {
    expect(formatTime(null)).toBe('—');
    expect(formatTime(undefined)).toBe('—');
    expect(typeof formatTime(Date.now())).toBe('string');
  });

  test('relativeTime produces human spans', () => {
    expect(relativeTime(Date.now() - 5000)).toMatch(/s ago/);
    expect(relativeTime(Date.now() - 3 * 60 * 1000)).toMatch(/m ago/);
    expect(relativeTime(null)).toBe('—');
  });

  test('prettyJson never throws on cycles', () => {
    const obj: any = {};
    obj.self = obj;
    expect(typeof prettyJson(obj)).toBe('string');
    expect(prettyJson({ a: 1 })).toContain('"a": 1');
  });
});
