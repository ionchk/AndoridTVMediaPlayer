"""
Playlist engine for Smart TV Media Player
Generates weighted playlists based on priority and sort order.
"""


def generate_playlist(media_files):
    """
    Generate a weighted playlist from active media files.
    
    Files with higher priority appear more frequently.
    Priority 10 = appears 10x more than priority 1.
    
    Args:
        media_files: list of MediaFile objects (already filtered to is_active=True)
    
    Returns:
        list of playlist entries (dicts) in display order
    """
    if not media_files:
        return []

    # Sort by sort_order first (base ordering)
    sorted_files = sorted(media_files, key=lambda f: f.sort_order)

    # Build weighted pool
    # Each file gets (priority) slots in the pool
    weighted_pool = []
    for media in sorted_files:
        entry = {
            'id': media.id,
            'filename': media.filename,
            'original_name': media.original_name,
            'file_type': media.file_type,
            'display_type': media.display_type,
            'display_duration': media.display_duration,
            'priority': media.priority,
            'converted_files': None
        }

        # For XLSX, include converted files
        if media.converted_files:
            import json
            try:
                entry['converted_files'] = json.loads(media.converted_files)
            except (json.JSONDecodeError, TypeError):
                entry['converted_files'] = []

        # Add (priority) copies to the pool
        for _ in range(media.priority):
            weighted_pool.append(entry)

    if not weighted_pool:
        return []

    # Distribute items evenly using round-robin across priority groups
    # This creates a sequence like: A, B, A, C, A, B, A...
    playlist = _distribute_weighted(sorted_files, weighted_pool)

    return playlist


def _distribute_weighted(sorted_files, weighted_pool):
    """
    Create an interleaved playlist using weighted distribution.
    Files with higher priority appear more often but are spread out evenly.
    """
    import json

    # Count total slots
    total_slots = sum(f.priority for f in sorted_files)

    if total_slots == 0:
        return []

    # Use fractional accumulation for even distribution
    entries = []
    for media in sorted_files:
        entry = {
            'id': media.id,
            'filename': media.filename,
            'original_name': media.original_name,
            'file_type': media.file_type,
            'display_type': media.display_type,
            'display_duration': media.display_duration,
            'priority': media.priority,
            'converted_files': None
        }
        if media.converted_files:
            try:
                entry['converted_files'] = json.loads(media.converted_files)
            except (json.JSONDecodeError, TypeError):
                entry['converted_files'] = []

        entries.append({
            'entry': entry,
            'weight': media.priority,
            'accumulator': 0.0
        })

    # Build playlist using bresenham-like algorithm
    playlist = []
    for _ in range(total_slots):
        # Add weight/total to each accumulator
        for item in entries:
            item['accumulator'] += item['weight']

        # Pick the item with highest accumulator
        best = max(entries, key=lambda x: x['accumulator'])
        playlist.append(best['entry'].copy())
        best['accumulator'] -= total_slots

    return playlist


def get_playlist_for_device(media_files):
    """
    Get full playlist data for a TV device.
    Returns a list of items to display in sequence with timing info
    for lightweight time-based synchronization.
    
    Each TV calculates: elapsed = now - server_time
    Then finds the correct item by checking cumulative_start times.
    This provides approximate sync without WebSockets.
    """
    import time

    raw_playlist = generate_playlist(media_files)

    # Expand XLSX entries (each sheet = separate entry)
    expanded = []
    for item in raw_playlist:
        if item['file_type'] == 'xlsx' and item.get('converted_files'):
            for conv_file in item['converted_files']:
                expanded_item = item.copy()
                expanded_item['display_file'] = conv_file
                expanded_item['display_type'] = 'image'
                expanded.append(expanded_item)
        else:
            item['display_file'] = item['filename']
            expanded.append(item)

    # Add cumulative timing for sync
    # Each item gets a cumulative_start (seconds from cycle start)
    cumulative = 0
    total_cycle_duration = 0
    for item in expanded:
        item['cumulative_start'] = cumulative
        cumulative += item['display_duration']
    total_cycle_duration = cumulative

    return {
        'items': expanded,
        'total_cycle_duration': total_cycle_duration,
        'server_time': time.time()
    }
