// Force-included into NRD's sources in the mingw build. MathLib calls abs() on floats
// unqualified: glibc's headers put the float overloads in the global namespace, mingw's
// only declare the C abs(int) there, which makes saturate(abs(x)) ambiguous.
#pragma once
#include <cmath>
#include <cstdlib>
using std::abs;
