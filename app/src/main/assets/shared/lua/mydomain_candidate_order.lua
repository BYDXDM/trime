-- Keep rare extended Han single-character candidates behind stronger ordinary ones.
local WINDOW_SIZE = 16

local function is_protected(cand)
  local kind = string.lower(tostring(cand.type or ""))
  return string.find(kind, "custom", 1, true) ~= nil
    or string.find(kind, "user", 1, true) ~= nil
    or string.find(kind, "sentence", 1, true) ~= nil
end

local function is_target(cand)
  if is_protected(cand) or type(cand.text) ~= "string" or cand.text == "" then
    return false
  end
  if not utf8 or type(utf8.len) ~= "function" or type(utf8.codepoint) ~= "function" then
    return false
  end
  local ok_len, length = pcall(utf8.len, cand.text)
  if not ok_len or length ~= 1 then
    return false
  end
  local ok_codepoint, codepoint = pcall(utf8.codepoint, cand.text)
  if not ok_codepoint or type(codepoint) ~= "number" then
    return false
  end
  return (codepoint >= 0x3400 and codepoint <= 0x4DBF) or codepoint > 0xFFFF
end

local function emit_window(window)
  local qualities = {}
  for i, item in ipairs(window) do
    local quality = tonumber(item.cand.quality)
    if not quality or quality ~= quality then
      for _, original in ipairs(window) do
        yield(original.cand)
      end
      return
    end
    qualities[i] = quality
  end

  local demoted = {}
  for i, item in ipairs(window) do
    if is_target(item.cand) then
      for j, other in ipairs(window) do
        if not is_target(other.cand) and qualities[j] > qualities[i] then
          demoted[i] = true
          break
        end
      end
    end
  end

  for i, item in ipairs(window) do
    if not demoted[i] then
      yield(item.cand)
    end
  end
  for i, item in ipairs(window) do
    if demoted[i] then
      yield(item.cand)
    end
  end
end

return function(input)
  local window = {}
  for cand in input:iter() do
    window[#window + 1] = { cand = cand }
    if #window == WINDOW_SIZE then
      emit_window(window)
      window = {}
    end
  end
  if #window > 0 then
    emit_window(window)
  end
end
